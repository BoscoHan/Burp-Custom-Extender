package burp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;


public class runPSCommand {

	public static void PSC(String PSCommand) throws IOException {
		  
		  //locations can be traced to XML config file
		  //getScript should return the command for automating BURP.
		  String command = PSCommand;
		 
		  
		  // Executing the command
		  Process powerShellProcess = Runtime.getRuntime().exec(command);
		  // Getting the results
		  powerShellProcess.getOutputStream().close();
		  
		  String line;
		  BufferedReader stdout = new BufferedReader(new InputStreamReader(
		    powerShellProcess.getInputStream()));
		  
		  while ((line = stdout.readLine()) != null) {
		   System.out.println(line);
		  }		  
/*		 
		  BufferedReader stderr = new BufferedReader(new InputStreamReader(
		    powerShellProcess.getErrorStream()));

		  while ((line = stderr.readLine()) != null) {
		   System.out.println(line);
		  }
*/		  	
		 }
	
	public static void RunPS(String PSCommand) throws IOException {
		String command = PSCommand;
		  // Executing the command
		  Process powerShellProcess = Runtime.getRuntime().exec(command);
		  // Getting the results
		  powerShellProcess.getOutputStream().close();
		  String line;
		  
		  BufferedReader stdout = new BufferedReader(new InputStreamReader(
		    powerShellProcess.getInputStream()));
		  while ((line = stdout.readLine()) != null) {
		  
		  }
		  stdout.close();
		  
		  BufferedReader stderr = new BufferedReader(new InputStreamReader(
		    powerShellProcess.getErrorStream()));
		  while ((line = stderr.readLine()) != null) {
		   System.out.println(line);
		  }
		  stderr.close();
		  

	}
	
	
	
}
