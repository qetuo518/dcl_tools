package dclnb.dcltools;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class ChatActivity extends AppCompatActivity {

	private static final String TOPIC = "dclnb";
	private static final String BASE_URL = "https://ntfy.sh/" + TOPIC;
	private static final String PREFS_NAME = "chat";
	private static final String KEY_USERNAME = "username";
	/** 列表最多显示的消息条数，超出后丢弃最旧的，避免消息过多导致卡顿/闪退 */
	private static final int MAX_MESSAGES = 100;
	/** 去重 id 缓存上限，防止长时间运行内存持续增长 */
	private static final int MAX_SEEN_IDS = 1000;

	private ListView listView;
	private EditText etInput;
	private Button btnSend;
	private ChatAdapter adapter;
	private List<ChatMsg> messages = new ArrayList<ChatMsg>();
	private Set<String> seenIds = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
	private Handler handler = new Handler(Looper.getMainLooper());
	private boolean running = true;
	private volatile String myUsername = "";
	private okhttp3.OkHttpClient wsClient;
	private okhttp3.WebSocket ws;

	/** 添加去重 ID，当缓存超过 MAX_SEEN_IDS 时移除最旧的 */
	private void addSeenId(String id) {
		synchronized (seenIds) {
			seenIds.add(id);
			if (seenIds.size() > MAX_SEEN_IDS) {
				// LinkedHashSet 保证顺序，移除第一个（最旧的）
				java.util.Iterator<String> it = seenIds.iterator();
				if (it.hasNext()) {
					it.next();
					it.remove();
				}
			}
		}
	}

	/** 添加消息并维护 MAX_MESSAGES 上限 */
	private void addMessage(ChatMsg msg) {
		messages.add(msg);
		adapter.notifyDataSetChanged();
		listView.setSelection(messages.size() - 1);
		// 超出上限时移除最旧的消息
		if (messages.size() > MAX_MESSAGES) {
			messages.remove(0);
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_chat);

		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		listView = (ListView) findViewById(R.id.list_view);
		etInput = (EditText) findViewById(R.id.et_input);
		btnSend = (Button) findViewById(R.id.btn_send);

		adapter = new ChatAdapter(this, messages);
		adapter.setOnFileClickListener(new ChatAdapter.OnFileClickListener() {
			@Override
			public void onFileClick(ChatMsg msg) {
				openOrDownloadFile(msg);
			}
		});
		listView.setAdapter(adapter);

		// 注册长按上下文菜单
		registerForContextMenu(listView);

		// 加载本地用户名
		SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
		myUsername = prefs.getString(KEY_USERNAME, "");
		if (myUsername == null || myUsername.length() == 0) {
			myUsername = "DCL用户" + (new Random().nextInt(9000) + 1000);
			prefs.edit().putString(KEY_USERNAME, myUsername).apply();
		}

		// 添加"改名"菜单
		toolbar.getMenu().clear();
		toolbar.getMenu().add(0, 100, 0, "改名").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				if (item.getItemId() == 100) {
					showRenameDialog();
					return true;
				}
				return false;
			}
		});

		Button btnAdd = (Button) findViewById(R.id.btn_add);
		if (btnAdd != null) {
			btnAdd.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showAddMenu();
				}
			});
		}

		btnSend.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				sendMessage();
			}
		});

		// 启动 WebSocket 长连接接收消息
		wsClient = new okhttp3.OkHttpClient.Builder()
			.connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
			.readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // 0 = 不超时，长连接
			.pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)    // 每20s发ping保活
			.build();
		connectWebSocket();
	}

	private void connectWebSocket() {
		okhttp3.Request request = new okhttp3.Request.Builder()
			.url("wss://ntfy.sh/" + TOPIC + "/ws")
			.build();
		okhttp3.WebSocketListener listener = new okhttp3.WebSocketListener() {
			@Override
			public void onOpen(okhttp3.WebSocket webSocket, okhttp3.Response response) {
				// 连接成功后拉取一次历史消息（与实时消息共用 seenIds 去重）
				fetchHistory();
			}

			@Override
			public void onMessage(okhttp3.WebSocket webSocket, String text) {
				if (text == null) {
					return;
				}
				text = text.trim();
				if (text.length() == 0 || text.startsWith(":")) {
					return; // 跳过 keep-alive 注释行
				}
				final ChatMsg msg = parseMessage(text);
				if (msg != null && msg.id != null && msg.id.length() > 0 && !seenIds.contains(msg.id)) {
					addSeenId(msg.id);
					handler.post(new Runnable() {
						@Override
						public void run() {
							addMessage(msg);
						}
					});
				}
			}

			@Override
			public void onClosed(okhttp3.WebSocket webSocket, int code, String reason) {
				// 服务端关闭，尝试重连
				reconnectIfAlive();
			}

			@Override
			public void onFailure(okhttp3.WebSocket webSocket, Throwable t, okhttp3.Response response) {
				// 网络异常，尝试重连
				reconnectIfAlive();
			}
		};
		ws = wsClient.newWebSocket(request, listener);
	}

	private void reconnectIfAlive() {
		if (!running) {
			return;
		}
		// 延迟1秒后重连
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (running) {
					connectWebSocket();
				}
			}
		}, 1000);
	}

	/** 一次性拉取 ntfy 缓存的历史消息（连接成功后调用） */
	private void fetchHistory() {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				okhttp3.Request request = new okhttp3.Request.Builder()
					.url(BASE_URL + "/json?poll=1")
					.build();
				okhttp3.Response response = null;
				try {
					response = wsClient.newCall(request).execute();
					if (!response.isSuccessful()) {
						return;
					}
					okhttp3.ResponseBody body = response.body();
					if (body == null) {
						return;
					}
					java.io.BufferedReader reader =
						new java.io.BufferedReader(new java.io.InputStreamReader(body.byteStream(), "UTF-8"));
					String line;
					while ((line = reader.readLine()) != null) {
						line = line.trim();
						if (line.length() == 0 || line.startsWith(":")) {
							continue;
						}
						if (!line.startsWith("{")) {
							continue;
						}
						final ChatMsg msg = parseMessage(line);
						if (msg != null && msg.id != null && msg.id.length() > 0 && !seenIds.contains(msg.id)) {
							addSeenId(msg.id);
							handler.post(new Runnable() {
								@Override
								public void run() {
									addMessage(msg);
								}
							});
						}
					}
					reader.close();
				} catch (Exception e) {
					// 历史拉取失败不影响实时接收
				} finally {
					if (response != null) {
						response.close();
					}
				}
				// 历史全部加入后统一刷新一次列表
				handler.post(new Runnable() {
					@Override
					public void run() {
						adapter.notifyDataSetChanged();
						listView.setSelection(messages.size() - 1);
					}
				});
			}
		});
		t.start();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		running = false;
		if (ws != null) {
			ws.cancel();
			ws = null;
		}
		if (wsClient != null) {
			wsClient.dispatcher().executorService().shutdown();
			wsClient.connectionPool().evictAll();
			wsClient = null;
		}
	}

	// ==================== 文件功能 ====================

	private static final int REQ_PICK_FILE = 2001;

	/** ntfy 单条消息附件大小上限（字节），超过则无法上传 */
	private static final long MAX_UPLOAD_SIZE = 15L * 1024 * 1024;

	/** 点击左下角 + 号弹出的功能选择菜单 */
	private void showAddMenu() {
		final String[] items = new String[] { "文件" };
		new AlertDialog.Builder(this)
			.setTitle("选择功能")
			.setItems(items, new android.content.DialogInterface.OnClickListener() {
				@Override
				public void onClick(android.content.DialogInterface dialog, int which) {
					if (which == 0) {
						pickFile();
					}
				}
			})
			.setNegativeButton("取消", null)
			.show();
	}

	/** 调起系统文件选择器（优先使用 Storage Access Framework） */
	private void pickFile() {
		try {
			Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
			intent.setType("*/*");
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			startActivityForResult(Intent.createChooser(intent, "选择要发送的文件"), REQ_PICK_FILE);
		} catch (Exception e) {
			toast("无法打开文件选择器：" + e.getMessage());
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK && data != null) {
			Uri uri = data.getData();
			if (uri != null) {
				uploadFile(uri);
			}
		}
	}

	/** 从 Uri 读取文件名 */
	private String getFileNameFromUri(Uri uri) {
		String name = null;
		android.database.Cursor cursor = null;
		try {
			cursor = getContentResolver().query(uri, null, null, null, null);
			if (cursor != null && cursor.moveToFirst()) {
				int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
				if (idx >= 0) {
					name = cursor.getString(idx);
				}
				if (name == null || name.length() == 0) {
					name = uri.getLastPathSegment();
				}
			}
		} catch (Exception e) {
			// ignore
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		if (name == null || name.length() == 0) {
			name = "未知文件";
		}
		return name;
	}

	/** 上传选定文件到 ntfy，成功后展示为文件消息 */
	private void uploadFile(final Uri uri) {
		final String originalName = getFileNameFromUri(uri);
		toast("正在发送文件：" + originalName);
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					// 获取附件大小
					long size = 0L;
					android.content.ContentResolver cr = getContentResolver();
					try (android.database.Cursor c = cr.query(uri, new String[]{"_size"}, null, null, null)) {
						if (c != null && c.moveToFirst()) {
							size = c.getLong(0);
						}
					}
					if (size <= 0) {
						try (java.io.InputStream is = cr.openInputStream(uri)) {
							if (is != null) {
								size = is.available();
							}
						}
					}
					if (size > MAX_UPLOAD_SIZE) {
						handler.post(new Runnable() {
							@Override
							public void run() {
								toast("文件过大（>15MB），不支持发送");
							}
						});
						return;
					}
					// 把文件体直接 POST 到主题（等同 curl -T file ntfy.sh/主题），ntfy 自动按附件处理
					java.net.HttpURLConnection uploadConn =
						(java.net.HttpURLConnection) new java.net.URL(BASE_URL).openConnection();
					uploadConn.setConnectTimeout(20000);
					uploadConn.setReadTimeout(60000);
					uploadConn.setRequestMethod("POST");
					uploadConn.setDoOutput(true);
					// octet-stream 强制按附件处理（避免被当成纯文本消息）
					uploadConn.setRequestProperty("Content-Type", "application/octet-stream");
					uploadConn.setRequestProperty("Filename", originalName);
					uploadConn.setRequestProperty("Title", myUsername == null ? "" : myUsername);
					try (java.io.InputStream is = cr.openInputStream(uri);
					     java.io.OutputStream os = uploadConn.getOutputStream()) {
						byte[] buf = new byte[8192];
						int n;
						while ((n = is.read(buf)) > 0) {
							os.write(buf, 0, n);
						}
					}
					int uploadCode = uploadConn.getResponseCode();
					String respBody = "";
					if (uploadCode >= 200 && uploadCode < 300) {
						// 响应为 JSON：{"id":"...","attachment":{"url":"https://ntfy.sh/file/xxx",...}}
						try (java.io.BufferedReader reader = new java.io.BufferedReader(
								new java.io.InputStreamReader(uploadConn.getInputStream(), "UTF-8"))) {
							StringBuilder sb = new StringBuilder();
							String line;
							while ((line = reader.readLine()) != null) {
								sb.append(line);
							}
							respBody = sb.toString();
						}
					}
					uploadConn.disconnect();

					if (uploadCode < 200 || uploadCode >= 300) {
						final int c = uploadCode;
						handler.post(new Runnable() {
							@Override
							public void run() {
								toast("发送失败，HTTP " + c);
							}
						});
						return;
					}
					// 解析响应：msgId 用于去重（防 WebSocket 回显出重复消息），fileUrl 用于下载
					String fileUrl = "";
					String msgId = "";
					try {
						org.json.JSONObject rj = new org.json.JSONObject(respBody);
						msgId = rj.optString("id", "");
						org.json.JSONObject att = rj.optJSONObject("attachment");
						if (att != null) {
							fileUrl = att.optString("url", "");
						}
					} catch (Exception ignore) {
					}
					if (msgId.length() > 0) {
						addSeenId(msgId);
					}

					// 上传成功，本地立即展示文件消息（id 已加入 seenIds，WebSocket 回显会被去重）
					final ChatMsg msg = new ChatMsg();
					msg.id = msgId.length() > 0 ? msgId : null;
					msg.fileName = originalName;
					msg.fileSize = size;
					msg.fileUrl = fileUrl;
					msg.username = myUsername;
					msg.message = "已发送文件：" + originalName;
					msg.time = System.currentTimeMillis();
					handler.post(new Runnable() {
						@Override
						public void run() {
							addMessage(msg);
						}
					});
				} catch (Exception e) {
					final String err = e.getMessage();
					handler.post(new Runnable() {
						@Override
						public void run() {
							toast("发送失败：" + err);
						}
					});
				}
			}
		});
		t.start();
	}

	/** 点击消息气泡中的文件卡片时调用 */
	private void openOrDownloadFile(ChatMsg msg) {
		if (msg.localPath != null && msg.localPath.length() > 0) {
			openFile(msg);
		} else if (msg.fileUrl != null && msg.fileUrl.length() > 0) {
			downloadFile(msg);
		} else {
			toast("无法找到附件信息");
		}
	}

	/** 从 ntfy 服务器下载附件到本地 Downloads */
	private void downloadFile(final ChatMsg msg) {
		toast("正在下载：" + msg.fileName);
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					// 使用 okhttp 下载
					okhttp3.Request req = new okhttp3.Request.Builder()
						.url(msg.fileUrl)
						.get()
						.build();
					final okhttp3.Response resp = wsClient.newCall(req).execute();
					if (!resp.isSuccessful()) {
						handler.post(new Runnable() {
							@Override
							public void run() {
								toast("下载失败：" + resp.code());
							}
						});
						return;
					}
					// 取响应头 Content-Disposition 里的文件名
					String disposition = resp.header("Content-Disposition");
					final String finalLocalName;
					String localName = msg.fileName;
					if (disposition != null) {
						for (String part : disposition.split(";")) {
							part = part.trim();
							if (part.toLowerCase().startsWith("filename=")) {
								localName = part.substring("filename=".length()).trim();
								if (localName.startsWith("\"") && localName.endsWith("\"")) {
									localName = localName.substring(1, localName.length() - 1);
								}
								break;
							}
						}
					}
					finalLocalName = localName;
					File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DCLChat");
					if (!dir.exists()) {
						dir.mkdirs();
					}
					File dest = new File(dir, localName);
					try (java.io.InputStream is = resp.body().byteStream();
					     java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
						byte[] buf = new byte[8192];
						int n;
						while ((n = is.read(buf)) > 0) {
							fos.write(buf, 0, n);
						}
					}
					final String localPath = dest.getAbsolutePath();
					msg.localPath = localPath;
					handler.post(new Runnable() {
						@Override
						public void run() {
							adapter.notifyDataSetChanged();
							toast("已保存到：" + finalLocalName);
							openFile(msg);
						}
					});
				} catch (Exception e) {
					final String err = e.getMessage();
					handler.post(new Runnable() {
						@Override
						public void run() {
							toast("下载失败：" + err);
						}
					});
				}
			}
		});
		t.start();
	}

	/** 打开已下载的文件 */
	private void openFile(ChatMsg msg) {
		if (msg.localPath == null || msg.localPath.length() == 0) {
			toast("文件尚未下载");
			return;
		}
		File f = new File(msg.localPath);
		if (!f.exists()) {
			toast("文件不存在");
			return;
		}
		try {
			// Android 7+ 禁止跨应用共享 file:// URI，必须用 FileProvider 转成 content://
			Uri fileUri;
			if (Build.VERSION.SDK_INT >= 24) {
				fileUri = androidx.core.content.FileProvider.getUriForFile(
					this, getPackageName() + ".fileprovider", f);
			} else {
				fileUri = Uri.fromFile(f);
			}
			// 按扩展名推断 MIME 类型，找不到时用 */*
			String mimeType = "*/*";
			int dot = f.getName().lastIndexOf('.');
			if (dot >= 0) {
				String ext = f.getName().substring(dot + 1).toLowerCase();
				String t = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
				if (t != null) {
					mimeType = t;
				}
			}
			Intent intent = new Intent(Intent.ACTION_VIEW);
			intent.setDataAndType(fileUri, mimeType);
			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(intent);
		} catch (Exception e) {
			toast("无法打开文件：" + e.getMessage());
		}
	}

	/** 在 Android 13 以上确保拥有存储权限（实际上 manifest 中已声明，这里只是兼容说明） */
	private void ensureStoragePermission() {
		// manifest 已声明 WRITE/READ_EXTERNAL_STORAGE 和 MANAGE_EXTERNAL_STORAGE
	}

	private ChatMsg parseMessage(String json) {
		// ntfy 每行返回一个完整 JSON 对象，用 JSONObject 解析更可靠
		try {
			org.json.JSONObject obj = new org.json.JSONObject(json);
			ChatMsg m = new ChatMsg();
			m.id = obj.optString("id", "");
			m.message = obj.optString("message", "");
			// 用户名通过 Title header 携带，JSON 响应里的 title 字段
			m.username = obj.optString("title", "");
			long t = obj.optLong("time", 0L);
			m.time = t * 1000L;
			// 附件信息：ntfy 的 attachment 对象包含 url / name / size
			org.json.JSONObject att = obj.optJSONObject("attachment");
			if (att != null) {
				m.fileName = att.optString("name", "");
				m.fileSize = att.optLong("size", 0L);
				if (m.fileName.length() == 0) {
					m.fileName = null;
				}
				// 附件下载地址：优先用 attachment.url（如 https://ntfy.sh/file/xxxxx）
				String attUrl = att.optString("url", "");
				if (attUrl.length() > 0) {
					m.fileUrl = attUrl;
				} else if (m.id != null && m.id.length() > 0) {
					// 兜底：按消息 ID 拼接
					m.fileUrl = BASE_URL + "/" + m.id;
				}
			}
			return m;
		} catch (Exception e) {
			return null;
		}
	}

	private void sendMessage() {
		final String content = etInput.getText().toString();
		if (content.length() == 0) {
			toast("请输入消息");
			return;
		}
		etInput.setText("");
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					URL url = new URL(BASE_URL);
					HttpURLConnection conn = (HttpURLConnection) url.openConnection();
					conn.setConnectTimeout(10000);
					conn.setReadTimeout(10000);
					conn.setRequestMethod("POST");
					conn.setDoOutput(true);
					conn.setRequestProperty("Content-Type", "text/plain;charset=UTF-8");
					// 用户名通过 ntfy 的 Title header 携带，接收端从 JSON 的 title 字段读取
					String nameToSend = myUsername == null ? "" : myUsername;
					conn.setRequestProperty("Title", nameToSend);
					OutputStream os = conn.getOutputStream();
					os.write(content.getBytes("UTF-8"));
					os.flush();
					os.close();
					int code = conn.getResponseCode();
					conn.disconnect();
					if (code >= 200 && code < 300) {
						handler.post(new Runnable() {
							@Override
							public void run() {
								// 发送成功，稍后轮询会拉到
							}
						});
					} else {
						final int c = code;
						handler.post(new Runnable() {
							@Override
							public void run() {
								toast("发送失败：" + c);
							}
						});
					}
				} catch (Exception e) {
				final String err = e.getMessage();
				handler.post(new Runnable() {
					@Override
					public void run() {
						toast("发送失败：" + err);
					}
				});
			}
		}
	});

		t.start();
	}

	private void showRenameDialog() {
		final EditText input = new EditText(this);
		input.setSingleLine(true);
		input.setHint("输入新名字，如：小明");
		// 去掉已有后缀，只显示基础名
		String baseName = myUsername;
		if (baseName != null) {
			int idx = baseName.lastIndexOf('#');
			if (idx > 0) {
				baseName = baseName.substring(0, idx);
			}
		}
		input.setText(baseName);
		input.setSelection(input.getText().length());

		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle("修改名字");
		builder.setMessage("保存后会自动加上 #随机4位数，避免重名");
		builder.setView(input);
		builder.setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
			@Override
			public void onClick(android.content.DialogInterface dialog, int which) {
				String raw = input.getText().toString().trim();
				if (raw.length() == 0) {
					toast("名字不能为空");
					return;
				}
				// 如果输入里已带 #，先去掉旧后缀
				int idx = raw.lastIndexOf('#');
				if (idx > 0) {
					raw = raw.substring(0, idx);
				}
				// 自动拼接 # 随机4位数（1000~9999）
				int num = new Random().nextInt(9000) + 1000;
				String newName = raw + "#" + num;
				myUsername = newName;
				SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
				prefs.edit().putString(KEY_USERNAME, newName).commit();
				toast("名字已改为：" + newName);
			}
		});
		builder.setNegativeButton("取消", null);
		builder.show();
	}

	private void toast(String msg) {
		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		if (v.getId() == R.id.list_view) {
			AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
			int position = info.position;
			if (position >= 0 && position < messages.size()) {
				menu.add(0, 1, 0, "复制")
					.setShortcut('1', 'c');
			}
		}
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		if (item.getItemId() == 1) {
			AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
			int position = info.position;
			if (position >= 0 && position < messages.size()) {
				ChatMsg msg = messages.get(position);
				if (msg != null && msg.message != null) {
					android.content.ClipboardManager clipboard =
						(android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
					if (clipboard != null) {
						android.content.ClipData clip =
							android.content.ClipData.newPlainText("聊天消息", msg.message);
						clipboard.setPrimaryClip(clip);
						toast("已复制");
						return true;
					}
				}
			}
		}
		return super.onContextItemSelected(item);
	}
}