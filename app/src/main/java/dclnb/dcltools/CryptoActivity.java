package dclnb.dcltools;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class CryptoActivity extends AppCompatActivity {

	private Spinner spAlgo;
	private EditText etInput, etKey, etOutput;
	private Button btnEncrypt, btnDecrypt;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_crypto);

		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		spAlgo = (Spinner) findViewById(R.id.sp_algo);
		etInput = (EditText) findViewById(R.id.et_input);
		etKey = (EditText) findViewById(R.id.et_key);
		etOutput = (EditText) findViewById(R.id.et_output);
		btnEncrypt = (Button) findViewById(R.id.btn_encrypt);
		btnDecrypt = (Button) findViewById(R.id.btn_decrypt);

		String[] algos = {"Base64", "AES", "DES", "MD5", "SHA-1"};
		ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
				android.R.layout.simple_spinner_item, algos);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spAlgo.setAdapter(adapter);

		spAlgo.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				updateKeyVisibility(position);
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		btnEncrypt.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				doEncrypt();
			}
		});

		btnDecrypt.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				doDecrypt();
			}
		});
	}

	private void updateKeyVisibility(int pos) {
		String algo = spAlgo.getItemAtPosition(pos).toString();
		boolean needKey = algo.equals("AES") || algo.equals("DES");
		findViewById(R.id.et_key).setVisibility(needKey ? View.VISIBLE : View.GONE);
		// MD5/SHA-1 可进行字典暴力破解
		btnDecrypt.setEnabled(true);
		// 哈希算法时按钮显示暴力破解
		if (algo.equals("MD5") || algo.equals("SHA-1")) {
			btnDecrypt.setText("暴力破解");
		} else {
			btnDecrypt.setText("解密");
		}
	}

	private String getInput() {
		return etInput.getText().toString();
	}

	private void doEncrypt() {
		String algo = spAlgo.getSelectedItem().toString();
		String input = getInput();
		if (input.length() == 0) {
			toast("请输入内容");
			return;
		}
		try {
			String result = null;
			if (algo.equals("Base64")) {
				result = Base64.encodeToString(input.getBytes("UTF-8"), Base64.NO_WRAP);
			} else if (algo.equals("AES")) {
				result = aesEncrypt(input);
			} else if (algo.equals("DES")) {
				result = desEncrypt(input);
			} else if (algo.equals("MD5")) {
				result = hash("MD5", input);
			} else if (algo.equals("SHA-1")) {
				result = hash("SHA-1", input);
			}
			etOutput.setText(result);
		} catch (Exception e) {
			toast("加密失败：" + e.getMessage());
		}
	}

	private void doDecrypt() {
		String algo = spAlgo.getSelectedItem().toString();
		String input = getInput();
		if (input.length() == 0) {
			toast("请输入内容");
			return;
		}
		try {
			if (algo.equals("Base64")) {
				byte[] decoded = Base64.decode(input, Base64.NO_WRAP);
				etOutput.setText(new String(decoded, "UTF-8"));
			} else if (algo.equals("AES")) {
				etOutput.setText(aesDecrypt(input));
			} else if (algo.equals("DES")) {
				etOutput.setText(desDecrypt(input));
			} else if (algo.equals("MD5") || algo.equals("SHA-1")) {
				// 字典暴力破解
				startBruteForce(algo, input);
				return;
			}
		} catch (Exception e) {
			toast("解密失败：" + e.getMessage());
		}
	}

	private void startBruteForce(final String algo, final String targetHash) {
		btnDecrypt.setEnabled(false);
		btnDecrypt.setText("破解中...");
		toast("正在使用字典暴力破解...");

		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					List<String> dictionary = loadDictionary();
					String foundPassword = null;

					for (String password : dictionary) {
						if (password.isEmpty()) continue;
						String computedHash = hash(algo, password);
						if (computedHash.equalsIgnoreCase(targetHash)) {
							foundPassword = password;
							break;
						}
					}

					final String finalPassword = foundPassword;
					new Handler(Looper.getMainLooper()).post(new Runnable() {
						@Override
						public void run() {
							btnDecrypt.setEnabled(true);
							btnDecrypt.setText("暴力破解");
							if (finalPassword != null) {
								etOutput.setText(finalPassword);
								toast("破解成功！密码: " + finalPassword);
							} else {
								toast("密码未在字典中找到");
							}
						}
					});
				} catch (final Exception e) {
					new Handler(Looper.getMainLooper()).post(new Runnable() {
						@Override
						public void run() {
							btnDecrypt.setEnabled(true);
							btnDecrypt.setText("暴力破解");
							toast("破解失败：" + e.getMessage());
						}
					});
				}
			}
		}).start();
	}

	private List<String> loadDictionary() throws Exception {
		List<String> lines = new ArrayList<>();
		InputStream is = getAssets().open("dclwl.txt");
		BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
		String line;
		while ((line = reader.readLine()) != null) {
			lines.add(line.trim());
		}
		reader.close();
		is.close();
		return lines;
	}

	private String aesEncrypt(String input) throws Exception {
		String key = etKey.getText().toString();
		if (key.length() == 0) {
			key = randomHex(16); // 16 字节 = 128 位
			etKey.setText(key);
		}
		byte[] raw = key.getBytes("UTF-8");
		// 规整为 16/24/32 字节
		raw = fitKey(raw, 16);
		SecretKeySpec spec = new SecretKeySpec(raw, "AES");
		Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE, spec);
		byte[] enc = cipher.doFinal(input.getBytes("UTF-8"));
		return Base64.encodeToString(enc, Base64.NO_WRAP);
	}

	private String aesDecrypt(String input) throws Exception {
		String key = etKey.getText().toString();
		if (key.length() == 0) {
			toast("请输入密钥");
			return "";
		}
		byte[] raw = fitKey(key.getBytes("UTF-8"), 16);
		SecretKeySpec spec = new SecretKeySpec(raw, "AES");
		Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, spec);
		byte[] dec = cipher.doFinal(Base64.decode(input, Base64.NO_WRAP));
		return new String(dec, "UTF-8");
	}

	private String desEncrypt(String input) throws Exception {
		String key = etKey.getText().toString();
		if (key.length() == 0) {
			key = randomHex(8); // 8 字节 = 64 位
			etKey.setText(key);
		}
		byte[] raw = fitKey(key.getBytes("UTF-8"), 8);
		SecretKeySpec spec = new SecretKeySpec(raw, "DES");
		Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE, spec);
		byte[] enc = cipher.doFinal(input.getBytes("UTF-8"));
		return Base64.encodeToString(enc, Base64.NO_WRAP);
	}

	private String desDecrypt(String input) throws Exception {
		String key = etKey.getText().toString();
		if (key.length() == 0) {
			toast("请输入密钥");
			return "";
		}
		byte[] raw = fitKey(key.getBytes("UTF-8"), 8);
		SecretKeySpec spec = new SecretKeySpec(raw, "DES");
		Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, spec);
		byte[] dec = cipher.doFinal(Base64.decode(input, Base64.NO_WRAP));
		return new String(dec, "UTF-8");
	}

	private String hash(String algo, String input) throws Exception {
		MessageDigest md = MessageDigest.getInstance(algo);
		byte[] digest = md.digest(input.getBytes("UTF-8"));
		StringBuilder sb = new StringBuilder();
		for (byte b : digest) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private byte[] fitKey(byte[] key, int len) {
		byte[] out = new byte[len];
		for (int i = 0; i < len; i++) {
			out[i] = key[i % key.length];
		}
		return out;
	}

	private String randomHex(int len) {
		String chars = "abcdef0123456789";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < len * 2; i++) {
			sb.append(chars.charAt((int) (Math.random() * chars.length())));
		}
		return sb.toString();
	}

	private void toast(String msg) {
		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
	}
}