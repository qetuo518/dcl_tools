package dclnb.dcltools;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * FileAnalyzerActivity - 文件分析器
 * 
 * 功能：
 *   1. 从主页进入：显示文件选择器，可选择本地文件进行分析
 *   2. 从其他应用分享：直接显示被分享文件的详细信息
 */
public class FileAnalyzerActivity extends AppCompatActivity {

    private static final String TAG = "FileAnalyzerActivity";
    private static final int REQUEST_CODE_PICK_FILE = 1001;
    
    private static final SimpleDateFormat DATE_FORMAT = 
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private LinearLayout llContent;
    private ScrollView scrollView;
    private TextView tvTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_analyzer);

        setupToolbar();
        
        llContent = (LinearLayout) findViewById(R.id.ll_content);
        scrollView = (ScrollView) findViewById(R.id.scroll_view);
        tvTitle = (TextView) findViewById(R.id.tv_title);

        // 检查是否是分享进入
        handleSharedIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedIntent(intent);
    }

    /**
     * 设置标题栏
     */
    private void setupToolbar() {
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("文件分析器");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    /**
     * 处理分享的文件
     */
    private void handleSharedIntent(Intent intent) {
        String action = intent.getAction();
        String type = intent.getType();

        // 清除之前的内容
        llContent.removeAllViews();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            // 单文件分享
            Uri fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (fileUri != null) {
                tvTitle.setText("分享文件分析");
                showFileDetail(fileUri);
            } else {
                showError("无法获取文件URI");
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            // 多文件分享
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null && !uris.isEmpty()) {
                tvTitle.setText("分享文件列表 (" + uris.size() + "个文件)");
                for (Uri uri : uris) {
                    showFileDetail(uri);
                }
            } else {
                showError("无法获取文件列表");
            }
        } else {
            // 从主页直接进入，显示文件选择器
            showFileChooser();
        }
    }

    /**
     * 显示文件选择器
     */
    private void showFileChooser() {
        tvTitle.setText("选择文件");
        
        // 添加选择文件按钮
        Button btnPickFile = new Button(this);
        btnPickFile.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        btnPickFile.setText("📂 浏览文件");
        btnPickFile.setTextSize(16);
        btnPickFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFilePicker();
            }
        });
        llContent.addView(btnPickFile);

        // 添加提示文字
        TextView tvHint = new TextView(this);
        tvHint.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tvHint.setText("点击按钮选择要分析的文件\n或从其他应用分享文件至此");
        tvHint.setTextSize(13);
        tvHint.setTextColor(0xFF9E9E9E);
        tvHint.setPadding(16, 16, 16, 16);
        llContent.addView(tvHint);

        scrollView.setVisibility(View.VISIBLE);
    }

    /**
     * 打开文件选择器
     */
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 允许选择多个文件
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_CODE_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK) {
            if (data != null) {
                ArrayList<Uri> uris = new ArrayList<>();
                
                // 处理多文件选择
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                } 
                // 处理单文件选择
                else if (data.getData() != null) {
                    uris.add(data.getData());
                }

                if (!uris.isEmpty()) {
                    tvTitle.setText("选择文件 (" + uris.size() + "个)");
                    llContent.removeAllViews();
                    for (Uri uri : uris) {
                        showFileDetail(uri);
                    }
                }
            }
        }
    }

    /**
     * 显示单个文件的详细信息
     */
    private void showFileDetail(Uri uri) {
        FileInfo info = getFileInfo(uri);
        if (info == null) {
            return;
        }

        // 文件卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 12);
        card.setLayoutParams(cardParams);
        card.setBackground(getResources().getDrawable(R.drawable.card_bg));
        card.setPadding(16, 16, 16, 16);

        // 文件名称
        addInfoRow(card, "文件名称", info.fileName);

        // 文件路径
        addInfoRow(card, "文件路径", info.filePath);

        // 文件大小
        addInfoRow(card, "文件大小", formatFileSize(info.fileSize));

        // 创建时间
        addInfoRow(card, "创建时间", info.createdAt);

        // 修改时间
        addInfoRow(card, "修改时间", info.modifiedAt);

        // MIME类型
        addInfoRow(card, "文件类型", info.mimeType);

        llContent.addView(card);
        scrollView.setVisibility(View.VISIBLE);
    }

    /**
     * 添加信息行
     */
    private void addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 4, 0, 4);
        row.setLayoutParams(rowParams);

        TextView tvLabel = new TextView(parent.getContext());
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                100, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvLabel.setText(label);
        tvLabel.setTextSize(13);
        tvLabel.setTextColor(0xFF757575);
        row.addView(tvLabel);

        TextView tvValue = new TextView(parent.getContext());
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        tvValue.setLayoutParams(valueParams);
        tvValue.setText(value);
        tvValue.setTextSize(14);
        tvValue.setTextColor(0xFF212121);
        tvValue.setSingleLine(false);
        tvValue.setLineSpacing(4, 1.0f);
        row.addView(tvValue);

        parent.addView(row);
    }

    /**
     * 获取文件信息
     */
    private FileInfo getFileInfo(Uri uri) {
        FileInfo info = new FileInfo();
        
        // 尝试从URI获取文件名
        String fileName = getFileNameFromUri(uri);
        info.fileName = fileName != null ? fileName : "未知文件";

        // 尝试获取文件路径
        String filePath = getFilePathFromUri(uri);
        info.filePath = filePath != null ? filePath : uri.toString();

        // 尝试获取文件元数据
        File file = filePath != null ? new File(filePath) : null;
        if (file != null && file.exists()) {
            info.fileSize = file.length();
            info.createdAt = formatTimestamp(file.lastModified());
            info.modifiedAt = formatTimestamp(file.lastModified());
        } else {
            // 尝试从ContentResolver获取
            Cursor cursor = null;
            try {
                ContentResolver cr = getContentResolver();
                String[] projection = {
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.DATE_MODIFIED
                };
                
                cursor = cr.query(uri, projection, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
                    int sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
                    int dateAddedIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED);
                    int dateModifiedIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED);
                    
                    if (nameIdx >= 0) {
                        info.fileName = cursor.getString(nameIdx);
                    }
                    if (sizeIdx >= 0) {
                        info.fileSize = cursor.getLong(sizeIdx);
                    }
                    if (dateAddedIdx >= 0) {
                        long dateAdded = cursor.getLong(dateAddedIdx);
                        info.createdAt = formatTimestamp(dateAdded * 1000);
                    }
                    if (dateModifiedIdx >= 0) {
                        long dateModified = cursor.getLong(dateModifiedIdx);
                        info.modifiedAt = formatTimestamp(dateModified * 1000);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "获取文件元数据失败: " + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        // 获取MIME类型
        info.mimeType = getContentResolver().getType(uri);
        if (info.mimeType == null) {
            info.mimeType = getMimeTypeFromFileName(info.fileName);
        }

        return info;
    }

    /**
     * 从URI获取文件名
     */
    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            Cursor cursor = null;
            try {
                ContentResolver cr = getContentResolver();
                String[] projection = {android.provider.OpenableColumns.DISPLAY_NAME};
                cursor = cr.query(uri, projection, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "获取文件名失败: " + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }

    /**
     * 从URI获取文件路径
     */
    private String getFilePathFromUri(Uri uri) {
        String path = null;
        
        // 如果是file:// scheme
        if ("file".equals(uri.getScheme())) {
            path = uri.getPath();
        } 
        // 如果是content:// scheme，尝试从MediaStore获取
        else if ("content".equals(uri.getScheme())) {
            Cursor cursor = null;
            try {
                ContentResolver cr = getContentResolver();
                String[] projection = {MediaStore.Files.FileColumns.DATA};
                cursor = cr.query(uri, projection, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                    if (index >= 0) {
                        path = cursor.getString(index);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "获取文件路径失败: " + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        
        return path;
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        double sizeBytes = size / Math.pow(1024, digitGroups);
        return String.format("%.2f %s", sizeBytes, units[digitGroups]);
    }

    /**
     * 格式化时间戳
     */
    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0) return "未知";
        return DATE_FORMAT.format(new Date(timestamp));
    }

    /**
     * 根据文件名获取MIME类型
     */
    private String getMimeTypeFromFileName(String fileName) {
        if (fileName == null) return "application/octet-stream";
        
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < fileName.length() - 1) {
            extension = fileName.substring(dotIndex + 1).toLowerCase(Locale.getDefault());
        }
        
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "pdf":
                return "application/pdf";
            case "txt":
                return "text/plain";
            case "zip":
                return "application/zip";
            case "apk":
                return "application/vnd.android.package-archive";
            default:
                return "application/octet-stream";
        }
    }

    /**
     * 显示错误信息
     */
    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * 文件信息数据类
     */
    private static class FileInfo {
        String fileName;
        String filePath;
        long fileSize;
        String createdAt;
        String modifiedAt;
        String mimeType;
    }
}