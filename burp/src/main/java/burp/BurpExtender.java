package burp;

import java.io.PrintWriter;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JLabel;
import javax.swing.ButtonGroup;
import javax.swing.JRadioButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JButton;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.Hashtable;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Iterator;
import java.util.Date;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.SimpleDateFormat;

/*Adds extra tab inside the Burp Pro application that has the functionality to export scanner reports in XML or HTML formats
 *Tab could be configured to load up on start to automatically handle report generation without user interference
 *All GUI components written with JSwing, commented for easy maintenance and feature addition
 *
 *Logic for automated report generation as follows:
 *First a configurable safety timer thread is registered to kill application if it idles for X amount of time, to facilitate automation 
 *
 *A volatile Boolean "finishedThread" handles when next threads and which next threads gets called {BURP api doesn't have a better method
 *to tell the machine when to quit. In fact we don't know when and if the scanner will receive further requests.}
 *
 *When !finishedThread, keep working on report generation and monitoring process
 *When finishedThread, schedule exit code from runnable, allow some time (configurable) for current processes to finish then execute exitCode
 * ---ExitCode is something we must call to exit gracefully without leaving garbage processes behind which continues to occupy connector PORT = BAD STUFF---
 *
 *When we run cURL/Selenium scripts, we can let the Extender handle the report generation, monitoring directory changes, email results and when to shutdown.
 *
 *The current logic for graceful shutdown: An active delayed poll(delay and poll times are all configurable) is used to monitor the
 *contents of the report directory(location is configurable). 
 *	When(old report = new report) { 
 *		toggle the volatile Boolean;
 *		next thread registered will be the 'shutdown runnable' instead of 'report runnable'... saves the extender options then proceeds to remove them first
 *		Right before shutdown, Burp will scan configuration to see if we should email the report to the recipients (Can turn off in XML)
 *		Burp will shutdown gracefully;
 *
 *
 *To configure, see the XML file in the userDIR
 */


public class BurpExtender implements IBurpExtender,ITab,ActionListener, IHttpListener, 
IProxyListener, IScannerListener, IExtensionStateListener {
	private IBurpExtenderCallbacks callbacks;
	private IExtensionHelpers helpers;
	private String name;
	private PrintWriter stdout;
	private Timer exitTimerTask;
	private Timer timerTask = new Timer();
	static ExtensionSymbolMap symbolmap = new ExtensionSymbolMap(new File(ExtensionControlClass.getConfigPath()));	
	static long currentFolderSize;
	
	//configuration fields
	private String reportFormat;
	private boolean inscopeOnly;
	private boolean mergeHttps; //merge http:80 and https:443 into one report
	private boolean mergeAll; //merge all protocols and ports into 1 report
	private File destDir;
	private boolean fileDate; //append generation date to filename in format MMDDYYYY
	
	//UI fields
	private JPanel component;
	private JRadioButton htmlButton;
	private JRadioButton xmlButton;
	private JCheckBox inscopeCheck;
	private JCheckBox httpsCheck;
	private JCheckBox mergeAllCheck;
	private JFileChooser destDirChooser;
	private JButton destDirButton;
	private JLabel destDirLabel;
	private JCheckBox filenameDateCheck;
	private JComboBox<String> dateFormatChooser;
	private JButton generateButton;
	private JButton customButton;
	private JLabel statusLabel;
	
	//constants
	private static final String VERSION = "0.6";
	private static final String[] dateFormats = {"MMddyyyy","ddMMyyyy","yyyyMMdd","MMddyy","ddMMyy","yyMMdd"};
	
	
	//toggle switch that decides whether to run next process, if we want to kill thread and exit, simply call stopThread() in our code
	volatile boolean finishedThread = false;
	  public void stopThread()
	  {
	    finishedThread = true;
	  }
	  public void restartThread() {
		  finishedThread = false;
	  }
	
	//IBurpExtender methods, Everything in here gets registered first when we first launch the extension.
	//Call any methods here and they will be run in order
	public void registerExtenderCallbacks(IBurpExtenderCallbacks cb) throws IOException {
		this.callbacks = cb;
		helpers = callbacks.getHelpers();
		name = "GETREPORT TAB";
		callbacks.setExtensionName(name+" v"+VERSION);		
		//initialized default settings
		reportFormat = "HTML";
		inscopeOnly = true;
		mergeHttps = true;
		mergeAll = false;
		destDir = new File(symbolmap.lookupSymbol("reportLocation")); //set directory for export report here
		fileDate = false;	
		callbacks.addSuiteTab(this);	
		
		stdout = new PrintWriter(cb.getStdout(), true);
	    
		//register ourselves as an HTTP listener
        //callbacks.registerHttpListener(this);
        
        // register ourselves as a Proxy listener
        callbacks.registerProxyListener(this);
        
        // register ourselves as a Scanner listener
        callbacks.registerScannerListener(this);
        
        // register ourselves as an extension state listener
        callbacks.registerExtensionStateListener(this);
        
        //register a timer to execute task in it:       
        forceKillProcessTimer();//register safety net, force kill app after a LONG time	
        WatchReport();       
	}
	
	
//---------------------------------------------------Timer Section -------------------------------------------------------	
	
	//this actually kills the remaining threads if we call exitcode!
	public void ScheduleExitTimer() {
		exitTimerTask.cancel(); //cancel the force kill timertask and replace with new one to exit right away
		exitTimerTask = new Timer();
		exitTimerTask.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {       
            		if(finishedThread) {
            			 stdout.println("Exit Timer Booked"); 
           				 callbacks.exitSuite(false); //THIS is the exit code to call			
            		}
            }
        }, Integer.parseInt(symbolmap.lookupSymbol("firstExitDelayTime")),Integer.parseInt(symbolmap.lookupSymbol("exitPollRate"))); 
		//first value is delay before getting executed, second value is repeat every x seconds...
	}
	
	//This method registers and generates report
	public void RescheduleTimer() {
		timerTask.cancel();
		timerTask = new Timer();
		timerTask.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
            	if(!finishedThread) {//while we don't want to finish off the thread, generate reports:
              stdout.println("Timer rescheduled");
              GenerateThread threadOBJ = new GenerateThread();//generating report
              threadOBJ.run();   
            	}
            }
        }, Integer.parseInt(symbolmap.lookupSymbol("firstReportDelayTime")),Integer.parseInt(symbolmap.lookupSymbol("reportPollRate"))); 
		//first value is delay before getting executed, second value is repeat every x seconds...
	}
	
	
	
	//force Kill still proceeds with removing the extension tab first, so all the code there gets run. THIS IS FAILSAFE, IN CASE PROCESSES DON'T QUIT
	public void forceKillProcessTimer() {
		
		stdout.println("ForceKill Timer registered to kill Application in 12 hours if inactive");
		exitTimerTask = new Timer();
		exitTimerTask.schedule(new TimerTask() {
            @Override
            public void run() {
            	{
            		callbacks.exitSuite(false);
            	}
            }
        }, Integer.parseInt(symbolmap.lookupSymbol("forceKillTimer")));//set safety net, 12 hour kill all thread n processes if app is idling
	}
	
	
	//this method monitors the file for when it was last modified!
	public void WatchReport() throws IOException {
		WatchService watchservice = FileSystems.getDefault().newWatchService();
		Path directory = Paths.get(symbolmap.lookupSymbol("reportLocation"));//directory of the reports!
		File reportfile = new File(symbolmap.lookupSymbol("reportLocation"));
		WatchKey watchkey = directory.register(watchservice,
				//StandardWatchEventKinds.ENTRY_CREATE,
				StandardWatchEventKinds.ENTRY_MODIFY);
		
		while (!finishedThread) {
			for (WatchEvent<?> event : watchkey.pollEvents()) {
				Path file = directory.resolve((Path) event.context());
				stdout.println(event.kind() + "  " + file + " was last modified at " + file.toFile().lastModified());			
				//stdout.println("size of the folder is: " + fileWork.reportFolderSize(reportfile) + " Bytes");//updates on every file modification
			}
		}
	}


	/* 
	 * 
	 * left out for now, could be used in future to run something else
	 * this timer can set the "delay" between the end of last task and the beginning of next task
	 * so no overlap thread issue whatsoever with unfinished tasks [Only runs class that implements runnable or extends thread]
	 * 
	 * 
	public void ScheduelThreadPool() {
		ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
		RunnableTask task1 = new RunnableTask ("Schedueled Task 1");
		
		//param1: name of the task; parm2: delay before first execution; param3: period between execution finish and next execution start
		ScheduledFuture<?> result = executor.scheduleWithFixedDelay(task1, 2, 5, TimeUnit.SECONDS);
		
		try {
            TimeUnit.MILLISECONDS.sleep(20000);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }     
        executor.shutdown();
    }
	*/	
	

	
	
	
	
	//ITab methods
	public String getTabCaption() {
		return name;
	}
	

	public Component getUiComponent() {
		component = new JPanel();
		
		JPanel innerPanel = new JPanel(new GridLayout(10,2,2,0));//sets the positions of the text
		innerPanel.add(new JLabel("Report Output Format:",SwingConstants.RIGHT));
		
		//RadioButton for html formats:
		htmlButton = new JRadioButton("HTML",true);
		htmlButton.addActionListener(this);
		htmlButton.setActionCommand("HTML");
		
		//RadioButton for html formats:
		xmlButton = new JRadioButton("XML",false);
		xmlButton.addActionListener(this);
		xmlButton.setActionCommand("XML");
		
		//group two buttons together:
		ButtonGroup bg = new ButtonGroup();
		bg.add(htmlButton);
		bg.add(xmlButton);
		JPanel buttonPanel = new JPanel(new GridLayout(2,1));
		buttonPanel.add(htmlButton);
		buttonPanel.add(xmlButton);
		innerPanel.add(buttonPanel);
		innerPanel.add(new JLabel("Report On In-Scope Sites Only:",SwingConstants.RIGHT));
		
		//report on in-scope sites only checkbox
		inscopeCheck = new JCheckBox((String) null,true);
		inscopeCheck.addActionListener(this);
		innerPanel.add(inscopeCheck);
		innerPanel.add(new JLabel("Merge HTTP (port 80) and HTTPS (port 443) For Reports:",SwingConstants.RIGHT));
		
		//One host per report checkbox
		httpsCheck = new JCheckBox((String) null,true);
		httpsCheck.addActionListener(this);
		innerPanel.add(httpsCheck);
		innerPanel.add(new JLabel("One Host Per Report (Combine All Protocols and Ports):",SwingConstants.RIGHT));
		
		//Report Directory checkbox:
		mergeAllCheck = new JCheckBox((String) null,false);
		mergeAllCheck.addActionListener(this);
		innerPanel.add(mergeAllCheck);
		innerPanel.add(new JLabel("Report Output Root Directory:",SwingConstants.RIGHT));
		
		//Select folder option
		destDirChooser = new JFileChooser();
		destDirChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		JPanel dirPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		
		//Btn to select folder prompt:
		destDirButton = new JButton("Select folder ...");
		destDirButton.addActionListener(this);
		destDirLabel = new JLabel(destDir.getAbsolutePath()); //Label that shows the current DIR selected
		dirPanel.add(destDirButton);
		dirPanel.add(destDirLabel);
		innerPanel.add(dirPanel);
		innerPanel.add(new JLabel("Append Date To Report Filenames:",SwingConstants.RIGHT));
		
		//date panel with checkbox, and combobox
		JPanel datePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		filenameDateCheck = new JCheckBox("Date Format:",false);
		filenameDateCheck.addActionListener(this);
		datePanel.add(filenameDateCheck);
		dateFormatChooser = new JComboBox<String>();
		
		for(int i=0;i<dateFormats.length;i++) {
			dateFormatChooser.addItem(dateFormats[i].toUpperCase());
		}
		dateFormatChooser.setEnabled(false);
		datePanel.add(dateFormatChooser);
		innerPanel.add(datePanel);
		
				//test btn to do custom action:
				customButton = new JButton("Custom Button");
				customButton.addActionListener(this);
				innerPanel.add(customButton);
				
		//button to generate report:
		generateButton = new JButton("Generate Report(s)");
		generateButton.addActionListener(this);
		innerPanel.add(generateButton);
		
		//label shows up when btn clicked, to show if report generation status
		statusLabel = new JLabel();
		innerPanel.add(statusLabel);
		component.add(innerPanel);
		callbacks.customizeUiComponent(component);
		return new JScrollPane(component);
	}
	
	
	
	
	
	
	//ActionListener methods
	public void actionPerformed(ActionEvent ae) {
		//source object handles all the different action events that we receive from user:
		Object source = ae.getSource();
		if((source == htmlButton) || (source == xmlButton)) {
			String comStr = ae.getActionCommand();
			if(comStr.equalsIgnoreCase("HTML")) {
				reportFormat = "HTML".toUpperCase();
			} else if(comStr.equalsIgnoreCase("XML")) {
				reportFormat = "XML".toUpperCase();
			}
		} else if(source == inscopeCheck) {
			JCheckBox jcb = (JCheckBox) source;
			inscopeOnly = jcb.isSelected();
		} else if(source == httpsCheck) {
			JCheckBox jcb = (JCheckBox) source;
			mergeHttps = jcb.isSelected();
		} else if(source == mergeAllCheck) {
			JCheckBox jcb = (JCheckBox) source;
			mergeAll = jcb.isSelected();
			if(mergeAll) {
				httpsCheck.setSelected(true);
				httpsCheck.setEnabled(false);
			} else {
				httpsCheck.setSelected(mergeHttps);
				httpsCheck.setEnabled(true);
			}
		} else if(source == destDirButton) {
			int res = destDirChooser.showOpenDialog(null);
			if(res == JFileChooser.APPROVE_OPTION) {
				destDir = destDirChooser.getSelectedFile();
				destDirLabel.setText(destDir.getAbsolutePath());
			}
		} else if(source == filenameDateCheck) {
			JCheckBox jcb = (JCheckBox) source;
			fileDate = jcb.isSelected();
			dateFormatChooser.setEnabled(fileDate);			
		} 
		
		//here is the trigger to start generating the report:
		//create the report on a new thread, can trigger this method in different ways:
		else if(source == generateButton) {
			Thread generateThread = new Thread(new GenerateThread());
			generateThread.start();
		}
		else if(source == customButton) {
			Thread generateThread = new Thread(new GenerateThread());
			generateThread.start();
		}
	}
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	//private "Report Generation Thread" class========================================================================================
	//all mechanism of report generation is listed, to use, call the runnable
	//NOTE: Don't need to necessarily finish everything before generating report, since it will just overwrite the last one
	private class GenerateThread implements Runnable {
		public void run() {
			
			IScanIssue[] issueListFull = callbacks.getScanIssues(null);
			if(issueListFull==null) issueListFull = new IScanIssue[0]; //if extension is loaded into Burp Suite Free: avoid NullPointerException here
			Hashtable<String,ArrayList<String>> sitesDict = new Hashtable<String,ArrayList<String>>();
			Set<String> siteKeys = sitesDict.keySet();
			
			for(int i=0;i<issueListFull.length;i++) {
				URL issueUrl = issueListFull[i].getUrl();
				if(inscopeOnly && !callbacks.isInScope(issueUrl)) continue; //if only reporting in-scope issues and URL is not in-scope: discard issue and continue
				
				String reqProt = issueUrl.getProtocol();
				String reqHost = issueUrl.getHost();
				int reqPort = issueUrl.getPort();
				ArrayList<String> ppList = null;
				
				if(!siteKeys.contains(reqHost)) {
					ppList = new ArrayList<String>();
					ppList.add(reqProt+":"+Integer.toString(reqPort));
					sitesDict.put(reqHost,ppList);
					siteKeys = sitesDict.keySet();
				} else {
					ppList = (ArrayList<String>) sitesDict.get(reqHost);
					boolean found = false;
					Iterator<String> ppListItr = ppList.iterator();
					
					while(ppListItr.hasNext()) {
						if(ppListItr.next().equals(reqProt+":"+Integer.toString(reqPort))) {
							found = true;
							break;
						}
					}
					if(!found) {
						ppList.add(reqProt+":"+Integer.toString(reqPort));
						sitesDict.put(reqHost,ppList);
					}
				}
			}
			
			
			//issues met criteria for reporting: generate reports
			if(!siteKeys.isEmpty()) {
				if(!destDir.exists()) { //if chosen output folder does not exist, create it
					if(!destDir.mkdirs()) {
						callbacks.printError(destDir.getAbsolutePath()+" could not be created!");
						statusLabel.setText("<html><font color=\'orange\'>"+destDir.getAbsolutePath()+" directory could not be created!</font></html>");					
						return;
					}
				} else if(!destDir.isDirectory()) { //if chosen output folder is not a directory
					callbacks.printError(destDir.getAbsolutePath()+" is not a directory!");
					statusLabel.setText("<html><font color=\'orange\'>"+destDir.getAbsolutePath()+" is not a directory!</font></html>");					
				}
				
				Hashtable<String,ArrayList<String>> reportList = new Hashtable<String,ArrayList<String>>();
				Iterator<String> siteKeysItr = siteKeys.iterator();
				
				while(siteKeysItr.hasNext()) {
					String site = siteKeysItr.next();
					ArrayList<String> ppList = sitesDict.get(site);
					
					if(mergeAll) {
						ArrayList<String> prefixList = new ArrayList<String>();
						Iterator<String> ppListItr = ppList.iterator();
						while(ppListItr.hasNext()) {
							String pp = ppListItr.next();
							String[] ppSplit = pp.split(":");
							if((ppSplit[0].equalsIgnoreCase("http")) && (ppSplit[1].equals("80"))) {
								prefixList.add("http://"+site+"/");
							} else if((ppSplit[0].equalsIgnoreCase("https")) && (ppSplit[1].equals("443"))) {
								prefixList.add("https://"+site+"/");
							} else {
								prefixList.add(ppSplit[0]+"://"+site+":"+ppSplit[1]+"/");
							}
						}
						if(prefixList.size()>0) {
							reportList.put(site+"_all",prefixList);
						}
					} else {
						if(mergeHttps) {
							ArrayList<String> stdPrefixList = new ArrayList<String>();
							boolean nonStdPort = false;
							Iterator<String> ppListItr = ppList.iterator();
							while(ppListItr.hasNext()) {
								String pp = ppListItr.next();
								String[] ppSplit = pp.split(":");
								if((ppSplit[0].equalsIgnoreCase("http")) && (ppSplit[1].equals("80"))) {
									stdPrefixList.add("http://"+site+"/");
								} else if((ppSplit[0].equalsIgnoreCase("https")) && (ppSplit[1].equals("443"))) {
									stdPrefixList.add("https://"+site+"/");
								} else {
									ArrayList<String> nonStdPrefixList = new ArrayList<String>(1);
									nonStdPrefixList.add(ppSplit[0]+"://"+site+":"+ppSplit[1]+"/");
									reportList.put(ppSplit[0]+"__"+site+"_"+ppSplit[1],nonStdPrefixList);
									nonStdPort = true;
								}
							}
							if(stdPrefixList.size()>0) {
								if(!nonStdPort) {
									reportList.put(site,stdPrefixList);
								} else {
									reportList.put("httphttps__"+site,stdPrefixList);
								}
							}
						} else {
							Iterator<String> ppListItr = ppList.iterator();
							while(ppListItr.hasNext()) {
								String pp = ppListItr.next();
								String[] ppSplit = pp.split(":");
								ArrayList<String> prefixList = new ArrayList<String>(1);
 								if((ppSplit[0].equalsIgnoreCase("http")) && (ppSplit[1].equals("80"))) {
									prefixList.add("http://"+site+"/");
									reportList.put(ppSplit[0]+"__"+site,prefixList);
								} else if((ppSplit[0].equalsIgnoreCase("https")) && (ppSplit[1].equals("443"))) {
									prefixList.add("https://"+site+"/");
									reportList.put(ppSplit[0]+"__"+site,prefixList);
								} else {
									prefixList.add(ppSplit[0]+"://"+site+":"+ppSplit[1]+"/");
									reportList.put(ppSplit[0]+"__"+site+"_"+ppSplit[1],prefixList);
								}
							}
						}
					}
				}
				
				Set<String> reportSites = reportList.keySet();
				Hashtable<String,IScanIssue[]> reportIssues = new Hashtable<String,IScanIssue[]>();
				Iterator<String> reportIssuesItr = reportSites.iterator();
				while(reportIssuesItr.hasNext()) {
					String site = reportIssuesItr.next();
					ArrayList<IScanIssue> issueList = new ArrayList<IScanIssue>();
					ArrayList<String> prefixList = reportList.get(site);
					Iterator<String> prefixListItr = prefixList.iterator();
					while(prefixListItr.hasNext()) {
						IScanIssue[] issueTempList = callbacks.getScanIssues(prefixListItr.next());
						for(int k=0;k<issueTempList.length;k++) {
							issueList.add(issueTempList[k]);
						}
					}
					if(issueList.size()>0) {
						IScanIssue[] tempArr = new IScanIssue[issueList.size()];
						IScanIssue[] issueArr = issueList.toArray(tempArr);
						reportIssues.put(site+"-burp."+reportFormat.toLowerCase(),issueArr);
					}
				}
				
				Set<String> reportFilenames = reportIssues.keySet();
				callbacks.printOutput("Starting report generation of "+Integer.toString(reportFilenames.size())+" reports");
				statusLabel.setText("<html><font color=\'orange\'>Starting report generation of "+Integer.toString(reportFilenames.size())+" reports...</font></html>");
				Iterator<String> reportFilenamesItr = reportFilenames.iterator();
				int count = 1;
				while(reportFilenamesItr.hasNext()) {
					String filename = reportFilenamesItr.next();
					statusLabel.setText("<html><font color=\'orange\'>Generating report "+Integer.toString(count)+" of "+Integer.toString(reportFilenames.size())+"...</font></html>");
					IScanIssue[] issueList = reportIssues.get(filename);
					if(fileDate) {
						filename = filename.substring(0,filename.length()-(reportFormat.toLowerCase().length()+1))+"-";
						SimpleDateFormat sdf = new SimpleDateFormat(dateFormats[dateFormatChooser.getSelectedIndex()]);
						filename += sdf.format(new Date())+"."+reportFormat.toLowerCase();
					}
					File reportFile = new File(destDir,filename);
					callbacks.generateScanReport(reportFormat.toUpperCase(),issueList,reportFile);
					callbacks.printOutput("Report "+Integer.toString(count)+" ("+reportFile.getAbsolutePath()+") of "+Integer.toString(reportFilenames.size())+" generated successfully!");
					count++;
				}
				callbacks.printOutput("Report Generation Complete!");
				statusLabel.setText("<html><font color=\'orange\'>Report Generation Complete!</font></html>");
				
			} else {
				callbacks.printError("No reports generated: No sites match requirements for report generation!");
				statusLabel.setText("<html><font color=\'orange\'>No reports generated: No sites match requirements for report generation!</font></html>");
			}		
			
			//filesize checking:
			File reportfile = new File(symbolmap.lookupSymbol("reportLocation"));
			stdout.println("size of the folder is: " + fileWork.reportFolderSize(reportfile) + " Bytes \n");//updates on every file generated
			if (BurpExtender.currentFolderSize != fileWork.reportFolderSize(reportfile)) {
				BurpExtender.currentFolderSize = fileWork.reportFolderSize(reportfile);	//new file size gets updated
				restartThread(); //do not kill application, scan probs not complete
			}
			else //if the size of the folder did not change after generating the new file, kill thread and exit
				stopThread();
		}
	}















	



	public void newScanIssue(IScanIssue issue) {
		stdout.println("New scan issue: " + issue.getIssueName() + " Details: " + issue.getIssueDetail());
		RescheduleTimer();
		ScheduleExitTimer();
	}



	public void processProxyMessage(boolean messageIsRequest, IInterceptedProxyMessage message) {
		stdout.println(
			(messageIsRequest ? "Proxy request to " : "Proxy response from ") +
	    	message.getMessageInfo().getHttpService());		
	}



	public void processHttpMessage(int toolFlag, boolean messageIsRequest, IHttpRequestResponse messageInfo) {
        stdout.println(
                (messageIsRequest ? "HTTP request to " : "HTTP response from ") +
                messageInfo.getHttpService() +
                " [" + callbacks.getToolName(toolFlag) + "]");
	}

	
	
	
	
	//For simplicity, we might as well call everything from this class since there won't be many functions and features
	public void extensionUnloaded() {
		stopThread(); //tell all threads to stop working from here on
		
		stdout.println("Extension was unloaded");		
		stdout.println("This is the user directory ==> " + System.getProperty("user.dir") + "\n");	
		//lists the directory:
		File folder = new File(System.getProperty("user.dir"));
		File[] listOfFiles = folder.listFiles();
		    for (int i = 0; i < listOfFiles.length; i++) {
		      if (listOfFiles[i].isFile()) {
		        stdout.println("File " + listOfFiles[i].getName());
		      } else if (listOfFiles[i].isDirectory()) {
		        stdout.println("Directory " + listOfFiles[i].getName());
		      }
		    }
		    
		stdout.println("Configuration file is located here: "+ ExtensionControlClass.getConfigPath());
		    
		//call the email script if allowed to send email:-----------------------------------------------checking config file:
		if (symbolmap.lookupSymbol("sendEmail").toLowerCase().equals("yes")) {
			stdout.println("\n >Running EmailScript: " + symbolmap.lookupSymbol("emailScriptLocation"));
			System.out.println("---Sending Email with attachments---");
			try {
				runPSCommand.RunPS(symbolmap.lookupSymbol("emailScriptLocation"));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		else {
			stdout.println("\n >Not running Email Script this time ");
			System.out.println("\n Not running email script this time ");
		}
			//exit out of BURP when unloaded based on XML config:
		if (symbolmap.lookupSymbol("exitOnRemovalOfExtension").toLowerCase().equals("yes")) {
			callbacks.exitSuite(false);//true for user prompt on exit box
		}
		return;
	}

	
	
	
	

	public void extensionLoaded() {
		// THis method is not being registered up by BURP for some reason
		// cannot remove interface method without removing it from the interface, but i don't want to mess with it.
	}
}