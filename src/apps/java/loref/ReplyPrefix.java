package apps.java.loref;

public enum ReplyPrefix {

	WELCOME_MESSAGE("%%%_welcome_message_%%%"), 
	UPTIME_MESSAGE("%%%_uptime__message_%%%"), 
	TORRENTS_LIST("%%%_torrent_list____%%%"), 
	COMMAND_RESPONSE("%%%_command_reply___%%%"), 
	DIRECTORY_CONTENT_RESPONSE("%%%_dir_content_____%%%"), 
	HOMEDIR_RESPONSE("%%%_home_directory__%%%"), 
	NOTIFICATION("%%%_notification____%%%"), 
	I_AM_ONLINE("%%%_i_am_online_____%%%");
	
	private String prefix;

	ReplyPrefix(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

}
