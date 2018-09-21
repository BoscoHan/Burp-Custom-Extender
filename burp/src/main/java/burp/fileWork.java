package burp;

import java.io.File;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class fileWork {
	//finds the size of a directory, recurse.
	public static long reportFolderSize(File directory) {
	    long length = 0;
	    for (File file : directory.listFiles()) {
	        if (file.isFile())
	            length += file.length();
	        else
	            length += reportFolderSize(file);
	    }
	    return length;
	}
}