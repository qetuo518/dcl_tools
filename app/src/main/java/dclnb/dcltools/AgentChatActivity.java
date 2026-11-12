package dclnb.dcltools;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * AgentChatActivity - Agent 聊天界面（支持 SSE 流式输出）。
 * AIDE 环境专用：无 Lambda、无 Kotlin、纯 Java 匿名内部类。
 */
public class AgentChatActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "agent_config";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";

    private static final int MAX_HISTORY = 30;
    private static final int MAX_TOOL_ROUNDS = 5;

    private Toolbar toolbar;
    private ScrollView scrollView;
    private LinearLayout llMessages;
    private ProgressBar pbLoading;
    private EditText etInput;
    private Button btnSend;

    private Handler handler = new Handler(Looper.getMainLooper());
    private okhttp3.OkHttpClient client;
    private volatile okhttp3.Call currentCall = null;
    private volatile boolean aiThinking = false;
    private List<JSONObject> history = new ArrayList<JSONObject>();
    private volatile boolean destroyed = false;
    private volatile boolean toolCallLimitReached = false; // 工具调用上限标志

    // 流式输出时持有的当前气泡引用，用于追加文本
    private TextView currentStreamingBubble = null;
    private StringBuilder currentStreamContent = new StringBuilder();

    private static final String SYSTEM_PROMPT =
             "你是一个具备工具调用能力的 Android Agent。\n"
             + "你可以通过以下格式输出工具调用指令（每条指令独占一行或嵌入回复中均可）：\n"
             + "\n"
             + "1. Shell 命令执行：\n"
             + "   ['tools':'executeShell','p':'0','run','ls -la']\n"
             + "   - p=0 表示普通 shell 执行；p=1 表示通过 Shizuku 以高权限执行。\n"
             + "   - run 后紧跟要执行的命令字符串（用单引号包裹）。\n"
             + "2. 文件下载：\n"
             + "   ['download_file':'https://example.com/file.apk':'/sdcard/Download/file.apk']\n"
             + "   - 第一个单引号内为下载地址，第二个单引号内为本地保存路径。\n"
             + "   - 此操作会通过 Shizuku 以高权限执行，使用 curl 下载。\n"
             + "\n"
             + "3. 创建文件：\n"
             + "   ['create_file':'/sdcard/test.txt':'Hello World']\n"
             + "   - 第一个单引号内为文件路径，第二个单引号内为文件内容。\n"
             + "   - 此操作会通过 Shizuku 以高权限执行，确保能创建到需要权限的位置。\n"
             + "\n"
             + "4. 删除文件：\n"
             + "   ['delete_file':'/sdcard/test.txt']\n"
             + "   - 单引号内为要删除的文件或目录路径。\n"
             + "   - 此操作会通过 Shizuku 以高权限执行。\n"
             + "\n"
             + "5. 列出目录：\n"
             + "   ['list_file':'/sdcard/Download']\n"
             + "   - 单引号内为目录路径。\n"
             + "   - 此操作会通过 Shizuku 以高权限执行。\n"
             + "\n"
             + "6. 读取文件：\n"
             + "   ['read_file':'/sdcard/test.txt']\n"
             + "   - 单引号内为文件路径。\n"
             + "   - 此操作会通过 Shizuku 以高权限执行。\n"
             + "\n"
             + "7. 搜索文件：\n"
             + "   ['find_file':'/sdcard':'*.apk']\n"
             + "   - 第一个单引号内为搜索目录，第二个单引号内为文件名模式（支持通配符）。\n"
             + "   - 此操作会通过 Shizuku 以高权限执行。\n"
             + "\n"
             + "8. 创建目录：\n"
             + "   ['create_dir':'/sdcard/Download/new_folder']\n"
             + "   - 单引号内为目录路径，会自动递归创建父目录。\n"
             + "   - 此操作会通过 Shizuku 以高权限执行。\n"
             + "\n"
             + "9. 无障碍点击（坐标）：\n"
             + "   ['click':'x','y']\n"
             + "   - x 和 y 为屏幕坐标（整数），通过无障碍服务查找对应节点并点击。\n"
             + "   - 需要无障碍服务已开启，否则失败。\n"
             + "\n"
             + "10. 无障碍点击（文本）：\n"
             + "    ['TextClick':'文本']\n"
             + "    - 在屏幕中查找包含指定文本的节点并点击。\n"
              + "11. 启动应用：\n"
              + "    ['launch_app':'包名']\n"
              + "    - 通过无障碍服务启动指定包名的应用。\n"
              + "    - 需要无障碍服务已开启，否则失败。\n"
              + "\n"
              + "12. 无障碍滑动（坐标）：\n"
              + "    ['swipe':'x1,y1','x2,y2']\n"
              + "    - 在屏幕上从 (x1,y1) 滑动到 (x2,y2)。\n"
              + "    - 需要无障碍服务已开启，否则失败。\n"
              + "\n"
              + "    - 需要无障碍服务已开启，否则失败。\n"
              + "\n"
              + "13. 剪贴板写入：\n"
              + "    ['setClip':'文本内容']\n"
              + "    - 将指定文本写入系统剪贴板。\n"
              + "    - 此操作为本地执行，无需额外权限。\n"
              + "\n"
              + "14. 剪贴板读取：\n"
              + "    ['getClip']\n"
              + "    - 读取当前系统剪贴板内容并返回。\n"
              + "    - 此操作为本地执行，无需额外权限。\n"
              + "\n"
              + "15. 文件复制：\n"
              + "    ['copy_file':'/sdcard/src.txt','/sdcard/dst.txt']\n"
              + "    - 从源路径复制到目标路径，通过 Shizuku 以高权限执行。\n"
              + "    - 目标路径的父目录不存在时会自动创建。\n"
              + "\n"
               + "16. 按键模拟：\n"
               + "    ['press':'值']\n"
               + "    - 模拟按键，值可以是:\n"
               + "      * 按键名称: BACK, HOME, MENU, ENTER, DEL, VOLUME_UP, VOLUME_DOWN, POWER 等\n"
               + "      * 或直接使用 KEYCODE 值（如 '67' 表示 Enter）\n"
               + "    - 通过 Shizuku 执行 input keyevent 命令。\n"
               + "\n"
               + "17. 获取屏幕文字：\n"
               + "    ['getText']\n"
               + "    - 获取当前屏幕上所有可见的文字内容，通过无障碍服务获取。\n"
               + "\n"
               + "18. 获取当前Activity：\n"
               + "    ['getActivity']\n"
               + "    - 获取当前前台Activity的包名和类名，通过无障碍服务或shell命令获取。\n"
               + "\n"
               + "重要规则：\n"
               + "- 当你需要执行操作时，直接在回复中写出上述格式的指令即可，系统会自动解析并执行。\n"
               + "- 执行结果会以 system 角色的消息返回给你，你可以据此继续推理或给出最终回答。\n"
               + "- 如果不需要调用工具，正常回答用户问题即可。\n"
               + "- 不要编造不存在的工具名或参数格式。";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_chat);

        // 权限预检：无 Shizuku 权限则禁止进入
        if (!checkShizukuAndBlock()) {
            return;
        }

        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        ImageView btnConfig = (ImageView) findViewById(R.id.btn_config);
        if (btnConfig != null) {
            btnConfig.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(AgentChatActivity.this, AgentConfigActivity.class));
                }
            });
        }

        scrollView = (ScrollView) findViewById(R.id.scrollView);
        llMessages = (LinearLayout) findViewById(R.id.ll_messages);
        pbLoading = (ProgressBar) findViewById(R.id.pb_loading);
        etInput = (EditText) findViewById(R.id.et_input);
        btnSend = (Button) findViewById(R.id.btn_send);

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (aiThinking) {
                    cancelRequest();
                } else {
                    sendMessage();
                }
            }
        });

        client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        // 将系统提示词作为第一条 history 消息注入，使 AI 知晓自身工具能力
        addHistory("system", SYSTEM_PROMPT);

        addMessage("system", "Agent 已就绪，请先在上方配置 API 地址 / Key / 模型，然后开始对话。");
    }

    // ==================== 消息 UI ====================

    /** 添加一条新消息气泡（非流式） */
    private void addMessage(final String role, final String content) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || isFinishing()) return;
                TextView tv = createBubble(role, content);
                llMessages.addView(tv);
                scrollToBottom();
            }
        });
    }

    /** 创建一个新的流式气泡并设为当前活跃气泡 */
    private void startStreamingBubble() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || isFinishing()) return;
                currentStreamContent.setLength(0);
                currentStreamingBubble = createBubble("assistant", "");
                llMessages.addView(currentStreamingBubble);
                scrollToBottom();
            }
        });
    }

    /** 向当前流式气泡追加文本 */
    private void appendStreamText(final String delta) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || isFinishing() || currentStreamingBubble == null) return;
                currentStreamContent.append(delta);
                currentStreamingBubble.setText(currentStreamContent.toString());
                scrollToBottom();
            }
        });
    }

    /** 结束流式输出，清除当前气泡引用 */
    private void endStreamingBubble() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                currentStreamingBubble = null;
            }
        });
    }

    private TextView createBubble(String role, String content) {
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(14);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setTextColor(Color.parseColor("#212121"));
        tv.setBackground(makeBubble(role));
        tv.setTextIsSelectable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), dp(6), dp(8), dp(6));
        tv.setLayoutParams(lp);
        return tv;
    }

    private GradientDrawable makeBubble(String role) {
        int color;
        if ("user".equals(role)) {
            color = Color.parseColor("#DCF8C6");
        } else if ("assistant".equals(role)) {
            color = Color.parseColor("#FFFFFF");
        } else {
            color = Color.parseColor("#FFF3CD");
        }
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), Color.parseColor("#E0E0E0"));
        return d;
    }

    private void scrollToBottom() {
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                if (scrollView != null) {
                    scrollView.fullScroll(View.FOCUS_DOWN);
                }
            }
        });
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    // ==================== 对话逻辑 ====================

    private void sendMessage() {
        final String text = etInput.getText().toString().trim();
        if (text.length() == 0) {
            toast("请输入消息");
            return;
        }
        etInput.setText("");
        addMessage("user", text);
        addHistory("user", text);
        sendToAI();
    }

    /** 把 AI 回复发送到 API（流式），并处理可能的工具调用（无限循环，直到无工具调用） */
    private void sendToAI() {
        aiThinking = true;
        toolCallLimitReached = false; // 重置工具调用上限标志
        handler.post(new Runnable() {
            @Override
            public void run() {
                btnSend.setText("停止");
                pbLoading.setVisibility(View.VISIBLE);
            }
        });
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int round = 0;
                    while (true) { // 无限循环，直到无工具调用或被取消
                        round++;
                        
                        // 流式调用，返回完整回复内容
                        String reply = callApiStreaming(history);
                        if (reply == null || reply.length() == 0) break;

                        // 流式结束后，将完整回复加入历史
                        addHistory("assistant", reply);

                        // 解析工具调用
                        String toolResult = processTools(reply);
                        if (toolResult == null) {
                            break; // 无工具调用，对话结束
                        }
                        addMessage("system", toolResult);
                        addHistory("system", toolResult);
                        // 继续让 AI 根据工具结果回复
                    }
                } catch (final Exception e) {
                    if (!aiThinking) return; // 已被取消，忽略异常
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || isFinishing()) return;
                            addMessage("system", "错误：" + e.getMessage());
                        }
                    });
                } finally {
                    final boolean wasThinking = aiThinking;
                    aiThinking = false;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || isFinishing()) return;
                            if (wasThinking) {
                                btnSend.setText("发送");
                                pbLoading.setVisibility(View.GONE);
                            }
                        }
                    });
                }
            }
        });
        t.start();
    }

    /** 取消当前正在进行的请求 */
    private void cancelRequest() {
        aiThinking = false;
        if (currentCall != null) {
            try {
                currentCall.cancel();
            } catch (Exception ignored) {}
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || isFinishing()) return;
                btnSend.setText("发送");
                pbLoading.setVisibility(View.GONE);
            }
        });
    }

    /**
      * SSE 流式调用 OpenAI 兼容 API。
      * 逐行读取 data: {...}，解析 delta.content 并实时更新 UI。
      * 返回完整的拼接回复内容（用于后续工具解析和历史记录）。
      */
     private String callApiStreaming(List<JSONObject> msgs) throws Exception {
         SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
         final String apiUrl = prefs.getString(KEY_API_URL, "");
         final String apiKey = prefs.getString(KEY_API_KEY, "");
         final String model = prefs.getString(KEY_MODEL, "");

         if (apiUrl.length() == 0 || apiKey.length() == 0 || model.length() == 0) {
             toast("请先配置 Agent（API地址/Key/模型）");
             return null;
         }

         JSONObject body = new JSONObject();
         body.put("model", model);
         body.put("stream", true);

         JSONArray messages = new JSONArray();
         for (int i = 0; i < msgs.size(); i++) {
             messages.put(msgs.get(i));
         }
         body.put("messages", messages);

         okhttp3.Request req = new okhttp3.Request.Builder()
                 .url(apiUrl)
                 .addHeader("Authorization", "Bearer " + apiKey)
                 .addHeader("Content-Type", "application/json")
                 .post(okhttp3.RequestBody.create(
                         okhttp3.MediaType.parse("application/json; charset=utf-8"), body.toString()))
                 .build();

         currentCall = client.newCall(req);
         okhttp3.Response resp = currentCall.execute();
         currentCall = null;
        try {
            if (!resp.isSuccessful()) {
                String errBody = "";
                try { errBody = resp.body().string(); } catch (Exception ignored) {}
                final String msg = "API 返回 " + resp.code() + "：" + errBody;
                toast(msg);
                return null;
            }

            // 开始流式气泡
            startStreamingBubble();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body().byteStream(), "UTF-8"));
            StringBuilder fullContent = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (destroyed) break;
                // SSE 格式：data: {"id":"...","choices":[{"delta":{"content":"..."}}]}
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                if (data.length() == 0) continue;

                try {
                    JSONObject json = new JSONObject(data);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject first = choices.optJSONObject(0);
                    if (first == null) continue;
                    JSONObject delta = first.optJSONObject("delta");
                    if (delta == null) continue;
                    String content = delta.optString("content", null);
                    if (content != null && content.length() > 0) {
                        fullContent.append(content);
                        appendStreamText(content);
                    }
                } catch (Exception ignored) {
                    // 单行解析失败不影响后续流
                }
            }
            reader.close();
            endStreamingBubble();

            String result = fullContent.toString();
            if (result.length() == 0) result = "（空回复）";
            return result;

        } finally {
            if (resp != null) resp.close();
        }
    }

    private void addHistory(String role, String content) {
        try {
            JSONObject m = new JSONObject();
            m.put("role", role);
            m.put("content", content);
            history.add(m);
            if (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
        } catch (Exception ignored) {}
    }

    // ==================== 工具解析与执行 ====================

    /** 工具调用结果；无工具调用返回 null */
    private String processTools(String content) {
        List<ToolCall> calls = parseToolCalls(content);
        if (calls.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            String result = executeTool(call);
            if (i > 0) sb.append("\n");
            sb.append("工具 [").append(call.name).append("] 执行结果：\n").append(result);
        }
        return sb.toString();
    }

    private String executeTool(final ToolCall call) {
        if ("executeShell".equals(call.name)) {
            return execShellTool(call.arg1, call.arg2);
        } else if ("download_file".equals(call.name)) {
            return downloadTool(call.arg1, call.arg2);
        } else if ("create_file".equals(call.name)) {
            return createFileTool(call.arg1, call.arg2);
        } else if ("delete_file".equals(call.name)) {
            return deleteFileTool(call.arg1);
        } else if ("list_file".equals(call.name)) {
            return listFileTool(call.arg1);
        } else if ("read_file".equals(call.name)) {
            return readFileTool(call.arg1);
        } else if ("find_file".equals(call.name)) {
            return findFileTool(call.arg1, call.arg2);
        } else if ("create_dir".equals(call.name)) {
            return createDirTool(call.arg1);
        } else if ("click".equals(call.name)) {
            return clickTool(call.arg1, call.arg2);
        } else if ("TextClick".equals(call.name)) {
            return textClickTool(call.arg1);
        } else if ("launch_app".equals(call.name)) {
            return launchAppTool(call.arg1);
        } else if ("swipe".equals(call.name)) {
            return swipeTool(call.arg1, call.arg2);
        } else if ("setClip".equals(call.name)) {
            return setClipTool(call.arg1);
        } else if ("getClip".equals(call.name)) {
            return getClipTool();
        } else if ("copy_file".equals(call.name)) {
            return copyFileTool(call.arg1, call.arg2);
        } else if ("press".equals(call.name)) {
            return pressTool(call.arg1);
        } else if ("getText".equals(call.name)) {
            return getTextTool();
        } else if ("getActivity".equals(call.name)) {
            return getActivityTool();
        }
        return "未知工具：" + call.name;
    }

    /** 创建文件工具 - 使用 Shizuku 执行创建命令 */
    private String createFileTool(String path, String content) {
        if (path == null || path.length() == 0) return "ERROR: 文件路径为空";
        // 使用 Shizuku 执行创建文件命令（确保父目录存在）
        String escapedPath = path.replace("\\", "\\\\").replace("'", "'\\''");
        String escapedContent = content != null ? content.replace("\\", "\\\\").replace("'", "'\\''") : "";
        String cmd = "mkdir -p '" + escapedPath.substring(0, escapedPath.lastIndexOf('/') + 1) + 
                     "' && printf '%s' '" + escapedContent + "' > '" + escapedPath + "'";
        String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
        if (result != null) {
            return "创建成功：" + path;
        }
        return "创建失败：" + path + "（Shizuku 不可用或未授权）";
    }

    /** 删除文件工具 - 使用 Shizuku 执行 rm 命令 */
    private String deleteFileTool(String path) {
        if (path == null || path.length() == 0) return "ERROR: 文件路径为空";
        String escapedPath = path.replace("'", "'\\''");
        String cmd = "rm -rf '" + escapedPath + "'";
        String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
        if (result != null) {
            return "删除成功：" + path;
        }
        return "删除失败：" + path + "（Shizuku 不可用或未授权）";
    }

    /** 列出目录工具 - 使用 Shizuku 执行 ls 命令 */
    private String listFileTool(String path) {
        if (path == null || path.length() == 0) return "ERROR: 目录路径为空";
        String escapedPath = path.replace("'", "'\\''");
        String cmd = "ls -la '" + escapedPath + "' 2>&1";
        String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
        if (result != null) {
            return result.trim().length() > 0 ? result.trim() : "目录为空或无法访问：" + path;
        }
        return "ERROR: 无法列出目录：" + path;
    }

    /** 读取文件工具 - 使用 Shizuku 执行 cat 命令 */
    private String readFileTool(String path) {
        if (path == null || path.length() == 0) return "ERROR: 文件路径为空";
        String escapedPath = path.replace("'", "'\\''");
        String cmd = "cat '" + escapedPath + "' 2>&1";
        String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
        if (result != null) {
            return result;
        }
        return "ERROR: 无法读取文件：" + path;
    }

    /** 搜索文件工具 - 使用 Shizuku 执行 find 命令 */
    private String findFileTool(String dir, String pattern) {
        if (dir == null || dir.length() == 0) return "ERROR: 搜索目录为空";
        if (pattern == null || pattern.length() == 0) pattern = "*";
        String escapedDir = dir.replace("'", "'\\''");
        String escapedPattern = pattern.replace("'", "'\\''");
        // 将通配符转换为 find 的 -iname 模式
        String cmd = "find '" + escapedDir + "' -name '" + escapedPattern + "' 2>/dev/null";
        String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
        if (result != null) {
            String trimmed = result.trim();
            return trimmed.length() > 0 ? trimmed : "未找到匹配文件";
        }
        return "ERROR: 搜索失败：" + dir;
    }

    /** 创建目录工具 - 使用 Shizuku 执行 mkdir -p */
    private String createDirTool(String path) {
        if (path == null || path.length() == 0) return "ERROR: 目录路径为空";
        // 使用 Shizuku 执行 mkdir -p 命令（递归创建目录）
        String escapedPath = path.replace("'", "'\\''");
        String cmd = "mkdir -p '" + escapedPath + "'";
        String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
        if (result != null) {
            return "创建成功：" + path;
        }
        return "创建失败：" + path + "（Shizuku 不可用或未授权）";
    }

    /** 执行 shell 命令。p=0 普通shell，p=1 Shizuku */
    private String execShellTool(String pStr, String cmd) {
        if (cmd == null || cmd.length() == 0) {
            return "ERROR: 命令为空";
        }
        int p;
        try {
            p = Integer.parseInt(pStr.trim());
        } catch (Exception e) {
            p = 0;
        }
        if (p == 1) {
            // Shizuku 执行
            String out = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
            if (out != null) return out.trim();
            return "ERROR: Shizuku 不可用或未授权";
        }
        // 普通 shell 执行
        return execShellOutput(cmd);
    }

    /** 普通 shell 执行并返回输出 */
    private String execShellOutput(String command) {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                BufferedReader errReader = new BufferedReader(new InputStreamReader(proc.getErrorStream(), "UTF-8"));
                StringBuilder errSb = new StringBuilder();
                String errLine;
                while ((errLine = errReader.readLine()) != null) {
                    errSb.append(errLine).append("\n");
                }
                errReader.close();
                String errMsg = errSb.toString().trim();
                return "ERROR: exit code " + exitCode
                        + (errMsg.length() > 0 ? "\n" + errMsg : "");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        } finally {
            if (proc != null) {
                try { proc.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    /** 下载文件工具 - 使用 Shizuku 执行 curl 命令 */
    private String downloadTool(String url, String path) {
        if (url == null || url.length() == 0) return "ERROR: 下载地址为空";
        if (path == null || path.length() == 0) return "ERROR: 下载路径为空";
        String escapedPath = path.replace("'", "'\\''");
        String escapedUrl = url.replace("'", "'\\''");
        // 确保父目录存在并下载文件
        String cmd = "mkdir -p '" + escapedPath.substring(0, Math.max(escapedPath.lastIndexOf('/'), 0) + 1) + 
                     "' && curl -f -L '" + escapedUrl + 
                     "' -o '" + escapedPath + "' 2>&1";
        String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
        if (result != null && !result.trim().contains("curl:") && !result.trim().contains("error")) {
            return "下载成功：" + path;
        }
        return "下载失败：" + url + "\n" + result;
    }

    /** 无障碍点击（坐标） */
    private String clickTool(String xStr, String yStr) {
        if (DclAccessibilityService.instance == null) {
            return "ERROR: 无障碍服务未启用";
        }
        int x, y;
        try {
            x = Integer.parseInt(xStr.trim());
            y = Integer.parseInt(yStr.trim());
        } catch (Exception e) {
            return "ERROR: 坐标解析失败：" + e.getMessage();
        }
        boolean ok = DclAccessibilityService.instance.click(x, y);
        return ok ? "点击成功 (" + x + "," + y + ")" : "点击失败 (" + x + "," + y + ")";
    }

    /** 无障碍点击（文本） */
    private String textClickTool(String text) {
        if (DclAccessibilityService.instance == null) {
            return "ERROR: 无障碍服务未启用";
        }
        if (text == null || text.length() == 0) {
            return "ERROR: 文本为空";
        }
        boolean ok = DclAccessibilityService.instance.textClick(text);
        return ok ? "点击成功：" + text : "未找到或点击失败：" + text;
    }
/** 启动应用 */
    private String launchAppTool(String packageName) {
        if (packageName == null || packageName.length() == 0) {
            return "ERROR: 包名为空";
        }
        // 优先使用无障碍服务（如果已连接）
        if (DclAccessibilityService.instance != null) {
            boolean ok = DclAccessibilityService.instance.launchApp(packageName);
            if (ok) {
                return "启动成功：" + packageName;
            }
        }
        // 降级：使用 Shizuku 或普通方式启动
        try {
            String escapedPkg = packageName.replace("'", "'\\''");
            String cmd = "monkey -p '" + escapedPkg + "' -c android.intent.category.LAUNCHER 1";
            String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
            if (result != null) {
                return "启动成功：" + packageName + "（通过Shizuku）";
            }
        } catch (Exception e) {}
        // 最后尝试普通方式
        try {
            android.content.Intent intent = new Intent(android.content.Intent.ACTION_MAIN);
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            intent.setPackage(packageName);
            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "启动成功：" + packageName;
        } catch (Exception e) {
            return "启动失败：" + packageName + "（" + e.getMessage() + "）";
        }
    }

    /** 无障碍滑动（坐标） */
    private String swipeTool(String startXY, String endXY) {
        if (DclAccessibilityService.instance == null) {
            return "ERROR: 无障碍服务未启用";
        }
        if (startXY == null || startXY.length() == 0 || endXY == null || endXY.length() == 0) {
            return "ERROR: 坐标参数为空，格式：'x1,y1','x2,y2'";
        }
        boolean ok = DclAccessibilityService.instance.swipe(startXY, endXY);
        return ok ? "滑动成功 (" + startXY + " -> " + endXY + ")" : "滑动失败 (" + startXY + " -> " + endXY + ")";
    }

    /** 剪贴板写入 */
    private String setClipTool(String text) {
        if (text == null) text = "";
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                android.content.ClipData clip = android.content.ClipData.newPlainText("agent_clip", text);
                clipboard.setPrimaryClip(clip);
                return "剪贴板写入成功（长度：" + text.length() + "）";
            }
            return "ERROR: 无法获取剪贴板服务";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** 剪贴板读取 */
    private String getClipTool() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                android.content.ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) {
                        return text.toString();
                    }
                }
            }
            return "剪贴板为空";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** 文件复制 - 使用 Shizuku 执行 cp 命令 */
    private String copyFileTool(String srcPath, String dstPath) {
        if (srcPath == null || srcPath.length() == 0) return "ERROR: 源路径为空";
        if (dstPath == null || dstPath.length() == 0) return "ERROR: 目标路径为空";
        String escapedSrc = srcPath.replace("'", "'\\''");
        String escapedDst = dstPath.replace("'", "'\\''");
        // 确保目标目录存在
        String dstDir = escapedDst.substring(0, Math.max(escapedDst.lastIndexOf('/'), 0) + 1);
        String cmd = "mkdir -p '" + dstDir + "' && cp -f '" + escapedSrc + "' '" + escapedDst + "' 2>&1";
        String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
        if (result != null) {
            return "复制成功：" + srcPath + " -> " + dstPath;
        }
        return "复制失败：" + srcPath + " -> " + dstPath + "（Shizuku 不可用或未授权）";
    }

    /** 按键模拟 - 通过 Shizuku 执行 input keyevent */
    private String pressTool(String keyName) {
        if (keyName == null || keyName.length() == 0) return "ERROR: 按键名称为空";
        String escapedKey = keyName.replace("'", "'\\''");
        String cmd = "input keyevent '" + escapedKey + "'";
        String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
        if (result != null) {
            return "按键成功：" + keyName;
        }
        return "按键失败：" + keyName + "（Shizuku 不可用或未授权）";
    }

    /** 获取屏幕文字 - 通过无障碍服务获取所有可见文字 */
    private String getTextTool() {
        if (DclAccessibilityService.instance == null) {
            return "ERROR: 无障碍服务未启用";
        }
        try {
            // 使用无障碍服务获取所有文本节点
            String text = DclAccessibilityService.instance.getAllText();
            if (text != null && text.length() > 0) {
                return "屏幕文字获取成功（共" + text.split("\n").length + "行）：\n" + text;
            } else {
                return "屏幕上没有可获取的文字内容";
            }
        } catch (Exception e) {
            return "ERROR: 获取屏幕文字失败：" + e.getMessage();
        }
    }

    /** 获取当前Activity - 通过shell命令获取前台Activity */
    private String getActivityTool() {
        try {
            // 使用dumpsys命令获取前台Activity
            String cmd = "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1";
            String result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
            
            if (result != null && result.length() > 0) {
                // 解析结果，提取包名和类名
                String activity = result;
                // 尝试提取 Activity 名称（格式：package/activity）
                int lastSlash = activity.lastIndexOf('/');
                if (lastSlash > 0) {
                    activity = activity.substring(lastSlash + 1);
                }
                return "当前Activity：" + activity;
            } else {
                // 如果dumpsys失败，尝试使用gettop
                cmd = "dumpsys activity top | grep ACTIVITY | head -n 1";
                result = ShellExecutor.execShizukuWithOutput(new String[]{"sh", "-c", cmd});
                if (result != null && result.length() > 0) {
                    return "当前Activity：\n" + result;
                }
                return "ERROR: 无法获取当前Activity";
            }
        } catch (Exception e) {
            return "ERROR: 获取Activity失败：" + e.getMessage();
        }
    }

    // ==================== 工具调用解析 ====================

    /** 在文本中提取所有 [ ... ] 顶层结构并解析为工具调用 */
    private List<ToolCall> parseToolCalls(String text) {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        if (text == null) return calls;
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("[", i);
            if (start < 0) break;
            int depth = 0;
            int end = -1;
            for (int j = start; j < text.length(); j++) {
                char c = text.charAt(j);
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) { end = j; break; }
                }
            }
            if (end < 0) break;
            String inner = text.substring(start + 1, end).trim();
            ToolCall call = parseSingleCall(inner);
            if (call != null) calls.add(call);
            i = end + 1;
        }
        return calls;
    }

    /** 解析单个 [...] 内部内容 */
    private ToolCall parseSingleCall(String inner) {
        if (inner == null || inner.length() == 0) return null;
        String trimmed = inner.trim();
        // ---- download_file 格式：['download_file':'下载地址':'下载到的路径'] ----
        String dft = stripLeadingQuotesAndWord(trimmed, "download_file");
        if (dft != null) {
            // 去掉开头的冒号
            String rest = dft.trim();
            if (rest.startsWith(":")) rest = rest.substring(1);
            rest = rest.trim();
            // 按单引号切分，得到 ['url', 'path'] 两段（忽略单引号内冒号）
            List<String> quotedParts = splitByQuote(rest);
            if (quotedParts.size() >= 2) {
                String url = unquote(quotedParts.get(0).trim());
                String path = unquote(quotedParts.get(1).trim());
                return new ToolCall("download_file", url, path);
            }
            return null;
        }
        // ---- 无参数工具：getClip / getText（无需任何参数） ----
        if (trimmed.equals("getClip") || trimmed.equals("'getClip'") 
            || trimmed.equals("getText") || trimmed.equals("'getText'")) {
            return new ToolCall("getText", null, null);
        }
        // ---- 单参数工具：delete_file / list_file / read_file / create_dir / TextClick / launch_app / setClip / press ----
        String[] singleArgTools = {"delete_file", "list_file", "read_file", "create_dir", "TextClick", "launch_app", "setClip", "press"};
        for (int t = 0; t < singleArgTools.length; t++) {
            String toolName = singleArgTools[t];
            String stripped = stripLeadingQuotesAndWord(trimmed, toolName);
            if (stripped != null) {
                String rest = stripped.trim();
                if (rest.startsWith(":")) rest = rest.substring(1);
                rest = rest.trim();
                List<String> qp = splitByQuote(rest);
                if (qp.size() >= 1) {
                    return new ToolCall(toolName, unquote(qp.get(0).trim()), null);
                }
                return null;
            }
        }

        // ---- 双参数工具：create_file / find_file / click / swipe / copy_file ----
        String[] doubleArgTools = {"create_file", "find_file", "click", "swipe", "copy_file"};
        for (int t = 0; t < doubleArgTools.length; t++) {
            String toolName = doubleArgTools[t];
            String stripped = stripLeadingQuotesAndWord(trimmed, toolName);
            if (stripped != null) {
                String rest = stripped.trim();
                if (rest.startsWith(":")) rest = rest.substring(1);
                rest = rest.trim();
                List<String> qp = splitByQuote(rest);
                if (qp.size() >= 2) {
                    return new ToolCall(toolName, unquote(qp.get(0).trim()), unquote(qp.get(1).trim()));
                }
                return null;
            }
        }

        // ---- tools 格式：['tools':'executeShell','p':'0/1','run','命令'] ----
        // 直接使用原始 trimmed 进行正则匹配（不要先去掉 'tools' 前缀）
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("'([^']*)'");
        java.util.regex.Matcher m = p.matcher(trimmed);
        java.util.List<String> quoted = new java.util.ArrayList<String>();
        while (m.find()) {
            quoted.add(m.group(1));
        }
        if (quoted.size() < 2) return null;
        // quoted[0]="tools", quoted[1]="executeShell", ...
        if (!"tools".equals(quoted.get(0))) return null;
        if (!"executeShell".equals(quoted.get(1))) return null;
        String pVal = "0";
        String cmd = "";
        // 查找 'p' 和 'run' 后面的值
        for (int i = 2; i < quoted.size() - 1; i++) {
            if ("p".equals(quoted.get(i)) && i + 1 < quoted.size()) {
                pVal = quoted.get(i + 1);
                i++;
            } else if ("run".equals(quoted.get(i)) && i + 1 < quoted.size()) {
                cmd = quoted.get(i + 1);
                i++;
            }
        }
        return new ToolCall("executeShell", pVal, cmd);
    }

    /** 若 s 以 word 开头（可带或不带单引号），返回去除该前缀后的剩余部分；否则返回 null */
    private String stripLeadingQuotesAndWord(String s, String word) {
        if (s == null || word == null) return null;
        if (s.startsWith("'" + word + "'")) {
            return s.substring(word.length() + 2);
        }
        if (s.startsWith(word)) {
            return s.substring(word.length());
        }
        return null;
    }

    /** 按分隔符分割，忽略单引号内的分隔符 */
    private List<String> splitTopLevel(String s, char sep) {
        List<String> parts = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            if (c == sep && !inQuote) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts;
    }

    /** 按单引号边界切分，提取引号内内容（返回去掉引号的片段列表） */
    private List<String> splitByQuote(String s) {
        List<String> parts = new ArrayList<String>();
        if (s == null) return parts;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'') {
                int j = s.indexOf('\'', i + 1);
                if (j < 0) {
                    parts.add(s.substring(i + 1));
                    break;
                }
                parts.add(s.substring(i + 1, j));
                i = j + 1;
            } else {
                // 跳过分隔的冒号/空格
                i++;
            }
        }
        return parts;
    }

    /** 去掉首尾单引号 */
    private String unquote(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** 内部工具调用描述 */
    private static class ToolCall {
        String name;
        String arg1;
        String arg2;
        ToolCall(String name, String arg1, String arg2) {
            this.name = name;
            this.arg1 = arg1;
            this.arg2 = arg2;
        }
    }

    // ==================== 其他 ====================

    /**
     * 预检 Shizuku 权限。
     * 未授权时弹窗说明并请求权限，返回 false 则调用方应禁止进入。
     */
    private boolean checkShizukuAndBlock() {
        final android.content.Context ctx = this;
        if (!EnvChecker.isShizukuReady()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed || isFinishing()) return;
                    new androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setTitle("需要 Shizuku 权限")
                            .setMessage("Agent 工具依赖 Shizuku 执行高权限命令（文件操作、Shell 等）。\n\n"
                                    + "请先安装并启动 Shizuku，然后在下方点击“授权”为 DCL 工具授权。")
                            .setPositiveButton("授权", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    PermissionChecker.requestShizukuPermission(ctx);
                                    PermissionChecker.addShizukuPermissionListener(1,
                                            new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                                                @Override
                                                public void onRequestPermissionResult(int requestCode, int grantResult) {
                                                    if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                        toast("Shizuku 权限已授权，可以正常使用 Agent");
                                                    } else {
                                                        toast("Shizuku 权限未授权，部分工具无法使用");
                                                    }
                                                    PermissionChecker.removeShizukuPermissionListener(this);
                                                }
                                            });
                                }
                            })
                            .setNegativeButton("退出", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    finish();
                                }
                            })
                            .setCancelable(false)
                            .show();
                }
            });
            return false;
        }
        // Shizuku 已就绪，同时提示无障碍服务状态
        if (!EnvChecker.isAccessibilityEnabledInSettings(this)) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed || isFinishing()) return;
                    new androidx.appcompat.app.AlertDialog.Builder(ctx)
                            .setTitle("建议开启无障碍服务")
                            .setMessage("无障碍服务可用于点击、读屏等 Agent 工具。\n\n"
                                    + "当前未开启，是否前往设置？")
                            .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    PermissionChecker.openAccessibilitySettings(ctx);
                                }
                            })
                            .setNegativeButton("稍后", null)
                            .show();
                }
            });
        }
        return true;
    }

    private void toast(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || isFinishing()) return;
                Toast.makeText(AgentChatActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }
}
