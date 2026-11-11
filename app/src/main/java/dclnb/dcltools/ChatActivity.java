package dclnb.dcltools;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
	private static final int MAX_MESSAGES = 100;
	private static final int MAX_SEEN_IDS = 1000;

	private ListView listView;
	private EditText etInput;
	private Button btnSend;
	private ChatAdapter adapter;
	private List<ChatMsg> messages = new ArrayList<ChatMsg>();
	private Set<String> seenIds = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());
	private Handler handler = new Handler(Looper.getMainLooper());
	/** 标记 Activity 是否仍在运行 */
	private volatile boolean running = true;
	/** 标记 Activity 是否已销毁，用于阻止所有 UI 操作 */
	private volatile boolean destroyed = false;
	private volatile String myUsername = "";
	private okhttp3.OkHttpClient wsClient;
	private okhttp3.WebSocket ws;
	/** 等待存储权限后的待下载消息 */
	private ChatMsg pendingDownloadMsg;

	/** 安全地发送 UI 更新，若 Activity 已销毁则跳过 */
	private void safePost(Runnable runnable) {
		if (!running || destroyed) return;
		handler.post(runnable);
	}

	/** 添加去重 ID，当缓存超过 MAX_SEEN_IDS 时移除最旧的 */
	private void addSeenId(String id) {
		synchronized (seenIds) {
			seenIds.add(id);
			if (seenIds.size() > MAX_SEEN_IDS) {
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
		if (destroyed || isDestroyed()) return;
		messages.add(msg);
		adapter.notifyDataSetChanged();
		listView.setSelection(messages.size() - 1);
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

		registerForContextMenu(listView);

		SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
		myUsername = prefs.getString(KEY_USERNAME, "");
		if (myUsername == null || myUsername.length() == 0) {
			myUsername = "DCL用户" + (new Random().nextInt(9000) + 1000);
			prefs.edit().putString(KEY_USERNAME, myUsername).apply();
		}

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

		wsClient = new okhttp3.OkHttpClient.Builder()
			.connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
			.readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
			.pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
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
				fetchHistory();
			}

			@Override
			public void onMessage(okhttp3.WebSocket webSocket, String text) {
				if (text == null) return;
				text = text.trim();
				if (text.length() == 0 || text.startsWith(":")) return;
				final ChatMsg msg = parseMessage(text);
				if (msg != null && msg.id != null && msg.id.length() > 0 && !seenIds.contains(msg.id)) {
					addSeenId(msg.id);
					safePost(new Runnable() {
						@Override
						public void run() {
							addMessage(msg);
						}
					});
				}
			}

			@Override
			public void onClosed(okhttp3.WebSocket webSocket, int code, String reason) {
				reconnectIfAlive();
			}

			@Override
			public void onFailure(okhttp3.WebSocket webSocket, Throwable t, okhttp3.Response response) {
				reconnectIfAlive();
			}
		};
		ws = wsClient.newWebSocket(request, listener);
	}

	private void reconnectIfAlive() {
		if (!running) return;
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
					if (!response.isSuccessful()) return;
					okhttp3.ResponseBody body = response.body();
					if (body == null) return;
					java.io.BufferedReader reader =
						new java.io.BufferedReader(new java.io.InputStreamReader(body.byteStream(), "UTF-8"));
					String line;
					while ((line = reader.readLine()) != null) {
						line = line.trim();
						if (line.length() == 0 || line.startsWith(":")) continue;
						if (!line.startsWith("{")) continue;
						final ChatMsg msg = parseMessage(line);
						if (msg != null && msg.id != null && msg.id.length() > 0 && !seenIds.contains(msg.id)) {
							addSeenId(msg.id);
							safePost(new Runnable() {
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
				safePost(new Runnable() {
					@Override
					public void run() {
						if (adapter != null) adapter.notifyDataSetChanged();
						if (listView != null) listView.setSelection(messages.size() - 1);
					}
				});
			}
		});
		t.start();
	}

	@Override
	protected void onDestroy() {
		running = false;
		destroyed = true;
		if (handler != null) {
			handler.removeCallbacksAndMessages(null);
		}
		if (ws != null) {
			try {
				ws.close(1000, "Activity destroyed");
			} catch (Exception e) {
				// 忽略关闭异常
			}
			ws = null;
		}
		// 不关闭 executorService，让 OkHttp 自然回收
		super.onDestroy();
	}

	// ==================== 文件功能 ====================

	private static final int REQ_PICK_FILE = 2001;
	/** 存储权限请求码 */
	private static final int REQ_STORAGE_PERMISSION = 1001;

	/** ntfy 单条消息附件大小上限（字节） */
	private static final long MAX_UPLOAD_SIZE = 15L * 1024 * 1024;

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

	private void uploadFile(final Uri uri) {
		final String originalName = getFileNameFromUri(uri);
		toast("正在发送文件：" + originalName);
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					long size = 0L;
					android.content.ContentResolver cr = getContentResolver();
					try (android.database.Cursor c = cr.query(uri, new String[]{"_size"}, null, null, null)) {
						if (c != null && c.moveToFirst()) {
							size = c.getLong(0);
						}
					}
					if (size <= 0) {
						try (InputStream is = cr.openInputStream(uri)) {
							if (is != null) {
								size = is.available();
							}
						}
					}
					if (size > MAX_UPLOAD_SIZE) {
						safePost(new Runnable() {
							@Override
							public void run() {
								toast("文件过大（>15MB），不支持发送");
							}
						});
						return;
					}
					HttpURLConnection uploadConn =
						(HttpURLConnection) new URL(BASE_URL).openConnection();
					uploadConn.setConnectTimeout(20000);
					uploadConn.setReadTimeout(60000);
					uploadConn.setRequestMethod("POST");
					uploadConn.setDoOutput(true);
					uploadConn.setRequestProperty("Content-Type", "application/octet-stream");
					uploadConn.setRequestProperty("Filename", originalName);
					uploadConn.setRequestProperty("Title", myUsername == null ? "" : myUsername);
					try (InputStream is = cr.openInputStream(uri);
					     OutputStream os = uploadConn.getOutputStream()) {
						byte[] buf = new byte[8192];
						int n;
						while ((n = is.read(buf)) > 0) {
							os.write(buf, 0, n);
						}
					}
					int uploadCode = uploadConn.getResponseCode();
					String respBody = "";
					if (uploadCode >= 200 && uploadCode < 300) {
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
						safePost(new Runnable() {
							@Override
							public void run() {
								toast("发送失败，HTTP " + c);
							}
						});
						return;
					}
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

					final ChatMsg msg = new ChatMsg();
					msg.id = msgId.length() > 0 ? msgId : null;
					msg.fileName = originalName;
					msg.fileSize = size;
					msg.fileUrl = fileUrl;
					msg.username = myUsername;
					msg.message = "已发送文件：" + originalName;
					msg.time = System.currentTimeMillis();
					safePost(new Runnable() {
						@Override
						public void run() {
							addMessage(msg);
						}
					});
				} catch (Exception e) {
					final String err = e.getMessage();
					safePost(new Runnable() {
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
			checkStoragePermissionAndDownload(msg);
		} else {
			toast("无法找到附件信息");
		}
	}

	/** 检查存储权限后下载文件 */
	private void checkStoragePermissionAndDownload(final ChatMsg msg) {
		this.pendingDownloadMsg = msg;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			downloadFile(msg);
			return;
		}
		if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
				== PackageManager.PERMISSION_GRANTED) {
			downloadFile(msg);
		} else {
			requestPermissions(new String[] { android.Manifest.permission.WRITE_EXTERNAL_STORAGE },
					REQ_STORAGE_PERMISSION);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQ_STORAGE_PERMISSION) {
			if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				ChatMsg msg = this.pendingDownloadMsg;
				this.pendingDownloadMsg = null;
				if (msg != null) {
					downloadFile(msg);
				}
			} else {
				this.pendingDownloadMsg = null;
				toast("需要存储权限才能下载文件");
				if (shouldShowRequestPermissionRationale(
						android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
					new AlertDialog.Builder(this)
						.setTitle("需要存储权限")
						.setMessage("下载文件需要存储权限，是否前往设置？")
						.setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
							@Override
							public void onClick(android.content.DialogInterface dialog, int which) {
								Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
								intent.setData(android.net.Uri.parse("package:" + getPackageName()));
								startActivity(intent);
							}
						})
						.setNegativeButton("取消", null)
						.show();
				}
			}
		}
	}

	/** 从 ntfy 服务器下载附件到本地 Downloads */
	private void downloadFile(final ChatMsg msg) {
		toast("正在下载：" + msg.fileName);
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					okhttp3.Request req = new okhttp3.Request.Builder()
						.url(msg.fileUrl)
						.get()
						.build();
					final okhttp3.Response resp = wsClient.newCall(req).execute();
					if (!resp.isSuccessful()) {
						safePost(new Runnable() {
							@Override
							public void run() {
								toast("下载失败：" + resp.code());
							}
						});
						return;
					}
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
					try (InputStream is = resp.body().byteStream();
					     FileOutputStream fos = new FileOutputStream(dest)) {
						byte[] buf = new byte[8192];
						int n;
						while ((n = is.read(buf)) > 0) {
							fos.write(buf, 0, n);
						}
					}
					final String localPath = dest.getAbsolutePath();
					msg.localPath = localPath;
					safePost(new Runnable() {
						@Override
						public void run() {
							adapter.notifyDataSetChanged();
							toast("已保存到：" + finalLocalName);
							openFile(msg);
						}
					});
				} catch (Exception e) {
					final String err = e.getMessage();
					safePost(new Runnable() {
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
			Uri fileUri;
			if (Build.VERSION.SDK_INT >= 24) {
				fileUri = androidx.core.content.FileProvider.getUriForFile(
					this, getPackageName() + ".fileprovider", f);
			} else {
				fileUri = Uri.fromFile(f);
			}
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

	private ChatMsg parseMessage(String json) {
		try {
			org.json.JSONObject obj = new org.json.JSONObject(json);
			ChatMsg m = new ChatMsg();
			m.id = obj.optString("id", "");
			m.message = obj.optString("message", "");
			m.username = obj.optString("title", "");
			long t = obj.optLong("time", 0L);
			m.time = t * 1000L;
			org.json.JSONObject att = obj.optJSONObject("attachment");
			if (att != null) {
				m.fileName = att.optString("name", "");
				m.fileSize = att.optLong("size", 0L);
				if (m.fileName.length() == 0) {
					m.fileName = null;
				}
				String attUrl = att.optString("url", "");
				if (attUrl.length() > 0) {
					m.fileUrl = attUrl;
				} else if (m.id != null && m.id.length() > 0) {
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
					String nameToSend = myUsername == null ? "" : myUsername;
					conn.setRequestProperty("Title", nameToSend);
					OutputStream os = conn.getOutputStream();
					os.write(content.getBytes("UTF-8"));
					os.flush();
					os.close();
					int code = conn.getResponseCode();
					conn.disconnect();
					if (code >= 200 && code < 300) {
						safePost(new Runnable() {
							@Override
							public void run() {
							}
						});
					} else {
						final int c = code;
						safePost(new Runnable() {
							@Override
							public void run() {
								toast("发送失败：" + c);
							}
						});
					}
				} catch (Exception e) {
					final String err = e.getMessage();
					safePost(new Runnable() {
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
				int idx = raw.lastIndexOf('#');
				if (idx > 0) {
					raw = raw.substring(0, idx);
				}
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