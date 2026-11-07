package dclnb.dcltools;

public class ChatMsg {
	public String message;
	public long time;
	public String id;
	public String username;

	/** 附件原始文件名，非空表示这是一条文件消息 */
	public String fileName;
	/** 附件大小（字节），来自 ntfy 的 attachment.size，未知为 0 */
	public long fileSize;
	/** 附件完整下载地址，如 https://ntfy.sh/file/xxxx.txt */
	public String fileUrl;
	/** 本地已保存路径（发送方为原文件路径，接收方为下载后的路径），为空表示未下载 */
	public String localPath;

	public boolean isFile() {
		return fileName != null && fileName.length() > 0;
	}
}