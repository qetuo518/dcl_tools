package dclnb.dcltools;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class SecurityActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_security);

		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		getSupportActionBar().setTitle("安全防护");
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		getSupportActionBar().setDisplayShowHomeEnabled(true);

		View cardShield = findViewById(R.id.card_shield);
		View cardRansomware = findViewById(R.id.card_ransomware);
		View cardVirusScan = findViewById(R.id.card_virus_scan);

		cardShield.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(SecurityActivity.this, DclaqActivity.class);
				startActivity(i);
			}
		});

		cardRansomware.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(SecurityActivity.this, RansomwareTrapActivity.class);
				startActivity(i);
			}
		});

		cardVirusScan.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(SecurityActivity.this, VirusScanActivity.class);
				startActivity(i);
			}
		});

        // 白名单管理
		View cardWhitelist = findViewById(R.id.card_whitelist);
		cardWhitelist.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent i = new Intent(SecurityActivity.this, WhitelistActivity.class);
				startActivity(i);
			}
		});
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}
}