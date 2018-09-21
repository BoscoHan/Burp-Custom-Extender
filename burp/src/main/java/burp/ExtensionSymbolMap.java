package burp;

import java.io.File;
import java.util.Properties;

//We look up the value of XML tag by looking up its key name
//This class directly communicates with the XML file passed in
//It fetches the content stored under XML tags by looking up the key values
public class ExtensionSymbolMap {
	private Properties symbolmap;
	
	public ExtensionSymbolMap(File file) {
		symbolmap = new Properties();
	
	
	try {
		//convert from XML to fill symbolmap
		symbolmap.loadFromXML(file.toURI().toURL().openStream());
	}
	catch(Exception e) {
		}
	}
	
	
	//variable length arguments are put into an array, access like an array
	public String lookupSymbol(String symbol, String... variables) {
		
		//by getting the xml key inside the tag
		String message = symbolmap.getProperty(symbol);
		if (message == null)
			return"";
		
		//return message in between the tags
		return String.format(message, variables);
		
	}
	
}