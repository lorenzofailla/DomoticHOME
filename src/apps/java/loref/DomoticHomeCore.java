package apps.java.loref;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import sun.misc.*;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPException;
import org.jxmpp.stringprep.XmppStringprepException;

import apps.java.loref.FTPUtilities;
import apps.java.loref.IMUtilities;
import apps.java.loref.IMUtilities.IMListener;
import apps.java.loref.LogUtilities;
import apps.java.loref.FTPUtilities.FTPUtilitiesListener;


public class DomoticHomeCore {
	
	private final static String USER = "lorenzofailla-home";
	private final static String PASSWORD = "fornaci12Home";
	private final static String SERVER = "alpha-labs.net";
	
	private final static String FTP_SERVER = "ftp.lorenzofailla.esy.es";
	private final static String FTP_USER = "u533645305";
	private final static String FTP_PASSWORD = "fornaci12Hostinger";
	private final static String FTP_REMOTE_DIRECTORY = "/public_html/RasPi-Storage";
	private final static int FTP_PORT = 21;

	/*
	private final static String REPLY_PREFIX___WELCOME_MESSAGE = "%%%_welcome_message_%%%";
	private final static String REPLY_PREFIX___UPTIME_MESSAGE = "%%%_uptime__message_%%%";
	private final static String REPLY_PREFIX___TORRENTS_LIST = "%%%_torrent_list____%%%";
	private final static String REPLY_PREFIX___COMMAND_RESPONSE = "%%%_command_reply___%%%";
	private final static String REPLY_PREFIX___DIRECTORY_CONTENT_RESPONSE = "%%%_dir_content_____%%%";
	private final static String REPLY_PREFIX___HOMEDIR_RESPONSE = "%%%_home_directory__%%%";
	private final static String REPLY_PREFIX___NOTIFICATION = "%%%_notification____%%%";
	private final static String REPLY_PREFIX___I_AM_ONLINE = "%%%_i_am_online_____%%%";
	*/
	
	private final static String REPLY___UNRECOGNIZED = "Unrecognized command.";

	private final static long RECONNECT_TIMEOUT_MS = 5000L; // millisecondi
	private final static long INACTIVITY_TIMEOUT = 60 * 15; // secondi
	private final static long TICK_TIME_MS = 100L; // millisecondi
	private final static long PERIODIC_REPORT_INTERVAL = 1L; // secondi

	private long lastActivityMark;
	boolean lockKeepAlive = false;

	private boolean loopFlag;
	private boolean reconnectFlag=true;

	private MainLoop mainLoop;
	private Thread mainThread;

	private Timer timer;

	private IMUtilities imUtilities = null;

	private boolean hasUnixFileSystem = false;
	private boolean hasTransmission_Remote = false;
	
	private String[] authorizedClients = null;
	
	private class MainLoop implements Runnable {

		boolean printPeriodicReport = false;

		public MainLoop() {

		}

		@Override
		public void run() {

			long elapsedTime;
			// TODO Auto-generated method stub
			while (loopFlag) {

				/*
				 * determina il tempo trascorso dall'ultima attività rilevata,
				 * in secondi
				 */

				elapsedTime = (long) ((System.nanoTime() - lastActivityMark) / 1000000000L);

				try {

					Thread.sleep(TICK_TIME_MS);
					
				} catch (InterruptedException e) {

					LogUtilities.printErrorLog(e);

				}

			}

			if (reconnectFlag) {

				init();

			} else {
				System.out.println(LogUtilities.basicLog("Program terminated by user request."));
				mainThread.interrupt();

				System.exit(0);
			}

		}

	};

	private class ShutdownHandler implements SignalHandler {

		@Override
		public void handle(Signal signal) {
			
			System.out.println(String.format("Ricevuto segnale '%s'", signal.getName()));
		}

	}

	private class ShutdownHook extends Thread {

		public void run() {

			System.out.println("Esecuzione thread di uscita");

		}
	}

	private ShutdownHandler shutdownHandler = new ShutdownHandler();
	private ShutdownHook shutdownHook = new ShutdownHook();

	public DomoticHomeCore() {

		// inizializza il signalhandler
		Signal.handle(new Signal("INT"), shutdownHandler);
		// Signal.handle(new Signal("TERM"), shutdownHandler);
		// Signal.handle(new Signal("KILL"), shutdownHandler);

		Runtime.getRuntime().addShutdownHook(shutdownHook);

		// rileva i servizi attivi sul sistema operativo
		retrieveServices();

		// avvia la sequenza di inizializzazione
		init();

	}

	private void processCommand(String cmdSender, String cmdHeader, String cmdBody){
		
		switch (cmdHeader) {

		case "__keepalive_timeout":

			LogUtilities.printSimpleLog("Keepalive received");
			lockKeepAlive = false;

			break;
			
		case "__quit":

			loopFlag = false;
			
			break;

		case "__requestWelcomeMessage":

			imUtilities.sendMessage(cmdSender, ReplyPrefix.WELCOME_MESSAGE.prefix() + USER + "@" + SERVER);
			imUtilities.sendMessage(cmdSender, ReplyPrefix.UPTIME_MESSAGE.prefix() + getUptime());

			break;

		case "__listTorrents":

			imUtilities.sendMessage(cmdSender,ReplyPrefix.TORRENTS_LIST.prefix()
					+ getTorrentsList());

			break;

		case "__beep":

			System.out.print("\007");

			break;

		case "__shutdown":

			try {

				parseShellCommand("sudo shutdown -h now");

			} catch (IOException | InterruptedException e) {

				LogUtilities.printErrorLog(e);

			}

			break;

		case "__reboot":

			try {

				parseShellCommand("sudo reboot");

			} catch (IOException | InterruptedException e) {

				LogUtilities.printErrorLog(e);

			}

			break;

		case "__execute_command":

			if (!cmdBody.equals("")) {
				try {

					imUtilities.sendMessage(cmdSender,
							ReplyPrefix.COMMAND_RESPONSE.prefix() + parseShellCommand(cmdBody));

				} catch (IOException | InterruptedException e) {

					LogUtilities.printErrorLog(e);

				}

			}

			break;

		case "__get_homedir":

			try {

				imUtilities.sendMessage(cmdSender, ReplyPrefix.HOMEDIR_RESPONSE.prefix() + parseShellCommand("pwd"));

			} catch (IOException | InterruptedException e) {

				LogUtilities.printErrorLog(e);

			}

			break;

		case "__get_directory_content":

			if (!cmdBody.equals("")) {

				try {

					imUtilities.sendMessage(cmdSender, ReplyPrefix.DIRECTORY_CONTENT_RESPONSE.prefix()
							+ parseShellCommand(String.format("ls %s -al", cmdBody)));

				} catch (IOException | InterruptedException e) {

					LogUtilities.printErrorLog(e);

				}

			}

			break;

		case "__get_file":

			if (!cmdBody.equals("")) {

				/*
				 * invia il file il cui percorso completo è
				 * rappresentato dalla stringa messageBody
				 */

				imUtilities.sendFile(cmdSender, cmdBody);

			}

			break;

		case "__upload_file_to_FTP":

			if (!cmdBody.equals("")) {

				/*
				 * invia il file il cui percorso completo è
				 * rappresentato dalla stringa commandBody
				 */

				LogUtilities.printSimpleLog("Initiating FTP upload required for: " + cmdBody);
				sendFileToFTPRemoteDirectory(cmdBody);

			}

			break;

		default:

			imUtilities.sendMessage(cmdSender, REPLY___UNRECOGNIZED + " [" + cmdBody + "]");
			LogUtilities.printSimpleLog("Unrecognized command: " + cmdBody + " from: " + cmdSender);

		} /* fine switch lettura comandi */
		
		
	}
	
	private void init() {

		IMListener imListener = new IMListener() {

			@Override
			public void onMessageReceived(String sender, String messageBody) {

				System.out.println(LogUtilities.basicLog(sender + " << " + messageBody));
				String[] commandSplit = messageBody.split(":::");
				String commandHeader = commandSplit[0];
				String commandBody = "";

				if (commandSplit.length > 1) {
					commandBody = commandSplit[1];
				}

				if (checkIfRecipientIsAuthorized(sender)) {

					processCommand(sender, commandHeader,commandBody);
					
					

									
				} else {

					LogUtilities.printSimpleLog("Unauthorized sender. Ignoring");

				} 

				// aggiorna il timestamp relativo all'ultima attività rilevata
				updateLastActivityMark();

			}

			@Override
			public void onConnected() {

				timer.cancel();
				System.out.println(LogUtilities.basicLog("Instant messaging service connected and listening."));

				// aggiorna il timestamp relativo all'ultima attività rilevata
				updateLastActivityMark();

				mainLoop = new MainLoop();
				mainThread = new Thread(mainLoop);
				mainThread.start();

			}

			@Override
			public void onConnectionInterrupted(Exception e) {
				/*
				 * La connessione è stata interrotta, interrompe il mainloop valorizzando a true il flag per la riconnessione
				 * 
				 * */

				LogUtilities.printErrorLog(e);

				System.out.println(LogUtilities.basicLog(
						String.format("Will try to reconnect again in %d seconds.", RECONNECT_TIMEOUT_MS / 1000)));

				reconnectFlag = true;
				loopFlag = false;

			}

			@Override
			public void onSendMessageException(String recipient, String message, Exception e) {
				// TODO Auto-generated method stub

			}

			@Override
			public void onSendFileException(String recipient, String fileDef, Exception e) {
				// TODO Auto-generated method stub

			}

			@Override
			public void onFTPUploadFileException(File file, Exception e) {
				// TODO Auto-generated method stub

			}

			@Override
			public void onConnectionClosed() {
				
				
			}

		};
		
		getConfig();
		
		loopFlag = true;
		imUtilities = new IMUtilities(USER, PASSWORD, SERVER);
		imUtilities.setIMListener(imListener);

		// avvio la procedura di connessione
		timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {

			@Override
			public void run() {

				System.out.println(LogUtilities.basicLog("Initiating connection..."));
				imUtilities.connect();

			}

		}, 0, RECONNECT_TIMEOUT_MS);

	}

	private void getConfig() {
		
		authorizedClients = new String[]{"lorenzofailla-controller@alpha-labs.net",""};
				
	}
	
	private boolean checkIfRecipientIsAuthorized(String recipient){
		
		return (Arrays.asList(authorizedClients).contains(recipient));
				
	}
	
	private void retrieveServices() {

		System.out.print("Checking 'uptime'...");
		try {

			parseShellCommand("uptime");
			System.out.print(" PASS.\n");
			
			hasUnixFileSystem=true;

		} catch (IOException | InterruptedException e) {

			System.out.print(" FAIL.\n");
			hasUnixFileSystem=false;
			
		}

		System.out.print("Checking 'transmission-remote'...");
		try {
			parseShellCommand("transmission-remote -n transmission:transmission -l");
			System.out.print(" PASS.\n");

			hasTransmission_Remote=true;
					
		} catch (IOException | InterruptedException e) {

			System.out.print(" FAIL.\n");
			hasTransmission_Remote=false;
		}
		
	}

	private String parseShellCommand(String command) throws IOException, InterruptedException {

		StringBuffer output = new StringBuffer();

		Process shellCommand;

		shellCommand = Runtime.getRuntime().exec(command);
		shellCommand.waitFor();

		BufferedReader reader = new BufferedReader(new InputStreamReader(shellCommand.getInputStream()));

		String line = "";

		while ((line = reader.readLine()) != null) {

			output.append(line + "\n");

		}

		return output.toString();
	}

	private String getUptime() {

		try {
			
			return parseShellCommand("uptime");
			
		} catch (IOException | InterruptedException e) {
			
			return null;
			
		}

	}

	private String getTorrentsList() {
		
		try {
			
			return parseShellCommand("transmission-remote -n transmission:transmission -l");
			
		} catch (IOException | InterruptedException e) {
			
			return null;
			
		}
		
	}
	
	private void updateLastActivityMark() {

		// aggiorna il timestamp relativo all'ultima attività rilevata
		lastActivityMark = System.nanoTime();

	}

	private void sendFileToFTPRemoteDirectory(String filenameURL) {

		new Thread(new SendFileToFTPThread(filenameURL)).start();

	}

	private class SendFileToFTPThread implements Runnable {

		private boolean transferCompleted = false;
		private boolean continueFlag = true;

		private String fileURL;

		FTPUtilitiesListener ftpUtilitiesListener = new FTPUtilitiesListener() {

			@Override
			public void onFileUploadComplete(String fileName) {

				transferCompleted = true;
				LogUtilities.printSimpleLog("FTP upload completed for: " + fileName);
			}

			@Override
			public void onFileUploadProgress(double progressValue) {

			}

			@Override
			public void onConnectionError(Exception e) {

				LogUtilities.printSimpleLog("FTP upload interrupted for CONNECTION ERROR " + e.getMessage());
				continueFlag = false;

			}

			@Override
			public void onFileUploadError(Exception e) {

				LogUtilities.printSimpleLog("FTP upload interrupted for UPLOAD ERROR " + e.getMessage());

			}

			@Override
			public void onConnected() {

				LogUtilities.printSimpleLog("FTP successfully connected.");

			}

			@Override
			public void onDisconnected() {

				LogUtilities.printSimpleLog("FTP successfully disconnected.");

			}

		};

		public SendFileToFTPThread(String fileToUpload) {

			fileURL = fileToUpload;

		}

		@Override
		public void run() {

			/* inizializza un FTPUtilities */
			FTPUtilities ftpUtilities = new FTPUtilities(FTP_SERVER, FTP_PORT, FTP_USER, FTP_PASSWORD,
					FTP_REMOTE_DIRECTORY);
			ftpUtilities.setListener(ftpUtilitiesListener);

			/* connette al server */
			ftpUtilities.connect();

			while (!ftpUtilities.isConnected && continueFlag) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {

				}

			}

			if (ftpUtilities.isConnected) {

				ftpUtilities.sendFile(fileURL);

				while (!transferCompleted) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {

					}

				}

				ftpUtilities.disconnect();

			}

		}

	}

}
