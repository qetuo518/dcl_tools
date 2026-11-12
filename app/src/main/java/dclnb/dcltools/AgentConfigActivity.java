package dclnb.dcltools;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class AgentConfigActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "agent_config";
    private static final String KEY_API_URL = "api_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";

    private EditText etApiUrl;
    private EditText etApiKey;
    private EditText etModel;
    private Button btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_config);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        etApiUrl = (EditText) findViewById(R.id.et_api_url);
        etApiKey = (EditText) findViewById(R.id.et_api_key);
        etModel = (EditText) findViewById(R.id.et_model);
        btnSave = (Button) findViewById(R.id.btn_save);

        loadConfig();

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
            }
        });
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        etApiUrl.setText(prefs.getString(KEY_API_URL, "https://api.deepseek.com/v1"));
        etApiKey.setText(prefs.getString(KEY_API_KEY, ""));
        etModel.setText(prefs.getString(KEY_MODEL, ""));
        etApiKey.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
    }

    private void saveConfig() {
        String apiUrl = etApiUrl.getText().toString().trim();
        String apiKey = etApiKey.getText().toString().trim();
        String model = etModel.getText().toString().trim();

        if (apiUrl.isEmpty()) {
            Toast.makeText(this, "请输入API地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "请输入API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        if (model.isEmpty()) {
            Toast.makeText(this, "请输入模型名称", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_API_URL, apiUrl)
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_MODEL, model)
                .apply();

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}