package burp;

import java.io.File;
import java.util.Collection;
import java.util.Iterator;

import org.apache.commons.io.FileUtils;


public class ExtensionControlClass {

	private static String ConfigDir;
	private boolean SendEmail;
	private String EmailScript;


	//Make sure the file path here ALWAYS exist, best practice is to keep it in the same directory as source code or in a clearly marked folder
	//This is the location of ---all the configurations--- (without this, everything won't work)
	//PUTTING THIS IN SAME DIR AS JAR FILE DURING RUN TIME!
	static ExtensionSymbolMap symbolmap = new ExtensionSymbolMap(new File(getConfigPath()));
	

	public static String getConfigPath() {
		String ExtensionConfigDir = System.getProperty("user.dir");
		ExtensionConfigDir += "\\locations.xml";
		return ExtensionConfigDir;
	}
	public static void setConfigPath(String Dir) {
		ExtensionControlClass.ConfigDir = Dir;
	}
	
	
	public static boolean isSendEmail() {
		if (symbolmap.lookupSymbol("sendEmail").toLowerCase().equals("yes")) {
			return true;
		}
		else return false;
	}
	public void setSendEmail(boolean sendEmail) {
		SendEmail = sendEmail;
	}
	
	
	//location of the email script powershell file:
	public static String getEmailScript() {
		return symbolmap.lookupSymbol("emailScriptLocation");
	}
	public void setEmailScript(String emailScript) {
		EmailScript = emailScript;
	}
	
	public static int getExitTimer() {
		//String number = symbolmap.lookupSymbol("exitTimer");
		//return Integer.parseInt(number);
		return 300000;
	}

	
	
}
