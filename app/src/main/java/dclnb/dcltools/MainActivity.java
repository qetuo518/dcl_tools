package dclnb.dcltools;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class MainActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		getSupportActionBar().setTitle("工具箱");

		LinearLayout cardChat = (LinearLayout) findViewById(R.id.card_chat);
		LinearLayout cardCrypto = (LinearLayout) findViewById(R.id.card_crypto);
		LinearLayout cardShell = (LinearLayout) findViewById(R.id.card_shell);
		LinearLayout cardSecurity = (LinearLayout) findViewById(R.id.card_security);

		cardChat.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(MainActivity.this, ChatActivity.class);
				startActivity(i);
			}
		});

		cardCrypto.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(MainActivity.this, CryptoActivity.class);
				startActivity(i);
			}
		});

		cardShell.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(MainActivity.this, ShellExecutorActivity.class);
				startActivity(i);
			}
		});

		cardSecurity.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(MainActivity.this, SecurityActivity.class);
				startActivity(i);
			}
		});

	}
}