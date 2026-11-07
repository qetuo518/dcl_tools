package dclnb.dcltools;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends BaseAdapter {

	private Context context;
	private List<ChatMsg> items;

	public ChatAdapter(Context context, List<ChatMsg> items) {
		this.context = context;
		this.items = items;
	}

	/** 记录当前被点击的文件消息，供 ChatActivity 打开 */
	public interface OnFileClickListener {
		void onFileClick(ChatMsg msg);
	}

	private OnFileClickListener fileClickListener;

	public void setOnFileClickListener(OnFileClickListener listener) {
		this.fileClickListener = listener;
	}

	@Override
	public int getCount() {
		return items.size();
	}

	@Override
	public Object getItem(int position) {
		return items.get(position);
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		View view = convertView;
		if (view == null) {
			LayoutInflater inflater = LayoutInflater.from(context);
			view = inflater.inflate(R.layout.item_chat, parent, false);
		}
		final ChatMsg msg = items.get(position);
		TextView tvUsername = (TextView) view.findViewById(R.id.tv_username);
		TextView tvMsg = (TextView) view.findViewById(R.id.tv_msg);
		TextView tvTime = (TextView) view.findViewById(R.id.tv_time);
		LinearLayout bubble = (LinearLayout) view.findViewById(R.id.bubble);
		LinearLayout root = (LinearLayout) view.findViewById(R.id.root);
		LinearLayout fileBox = (LinearLayout) view.findViewById(R.id.file_box);
		TextView tvFileName = (TextView) view.findViewById(R.id.tv_file_name);
		TextView tvFileSize = (TextView) view.findViewById(R.id.tv_file_size);

		if (msg != null) {
			// 文件消息：隐藏文本，显示文件卡片；普通消息反之
			boolean isFile = msg.isFile();
			tvMsg.setVisibility(isFile ? View.GONE : View.VISIBLE);
			if (!isFile) {
				tvMsg.setText(msg.message);
			}
			fileBox.setVisibility(isFile ? View.VISIBLE : View.GONE);
			if (isFile) {
				tvFileName.setText(msg.fileName);
				tvFileSize.setText(formatSize(msg.fileSize));
				fileBox.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (fileClickListener != null) {
							fileClickListener.onFileClick(msg);
						}
					}
				});
			}
			// 用户名显示，为空则隐藏
			if (msg.username != null && msg.username.length() > 0) {
				tvUsername.setText(msg.username);
				tvUsername.setVisibility(View.VISIBLE);
			} else {
				tvUsername.setVisibility(View.GONE);
			}
			if (msg.time > 0) {
				SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
				tvTime.setText(sdf.format(new Date(msg.time)));
			} else {
				tvTime.setText("");
			}
			// 统一靠左显示
			root.setGravity(android.view.Gravity.START);
			bubble.setBackgroundResource(R.drawable.bubble_bg);
		}
		return view;
	}

	/** 格式化文件大小 */
	private String formatSize(long bytes) {
		if (bytes <= 0) {
			return "未知大小";
		}
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
		}
		return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
	}
}