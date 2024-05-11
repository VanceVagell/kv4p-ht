package org.jdamico.javax25;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;


public class BasicHelper {

	private static BasicHelper INSTANCE = null;
	private BasicHelper(){}
	public static BasicHelper getInstance(){
		if(null == INSTANCE) INSTANCE = new BasicHelper();
		return INSTANCE;
	}

	public String dateToString(Date date, String format){
		String strDate = null;
		try {
			SimpleDateFormat formatter = new SimpleDateFormat(format);
			strDate = formatter.format(date);
		}catch (NullPointerException e) {}
		return strDate;

	}

	public void posixKill(String signal, String pid)  {

		try {
			Runtime runtime = Runtime.getRuntime();
			runtime.exec("kill -"+signal+" "+pid);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public Date stringToDate(String dateStr, String format) throws Exception{

		DateFormat dateFormat = new SimpleDateFormat(format, Locale.ENGLISH);
		Date date = null;

		date = dateFormat.parse(dateStr);

		return date;
	}

	public long getDiffHoursBetweenDates(Date init, Date end) {
		long diff = end.getTime() - init.getTime();
		diff = TimeUnit.HOURS.convert(diff, TimeUnit.MILLISECONDS);
		return diff;
	}

	public String readTextFileToString(File source) throws Exception {
		StringBuffer output = null;



		if(source != null && source.exists() && source.isFile()) {
			Path path = source.toPath();
			try {
				List<String> lst = Files.readAllLines(path);
				output = new StringBuffer();
				for (int i = 0; i < lst.size(); i++) {
					output.append(lst.get(i)+"\n");
				}
			} catch (IOException e) {
				throw new Exception("invalid file");
			}
		}else throw new Exception("invalid file");



		return output.toString();
	}

	public void writeStrToFile(String str, String fileName) throws IOException{

		File file = new File(fileName);
		writeStrToFile(str, file);
	}

	
	public void writeStrToFile(String str, File file) throws IOException{

		FileWriter fw = null;
		BufferedWriter out = null;
		try {
			fw = new FileWriter(file);
			out = new BufferedWriter(fw);
			out.write(str);  
		}finally{
			if(out != null)

				out.close();

			if(fw != null)

				fw.close();

		}	
	}
	
	
	public String listToString(List<String> lst) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < lst.size(); i++) {
			if(i + 1 == lst.size()) sb.append(lst.get(i));
			else sb.append(lst.get(i)+", ");
		}
		return sb.toString();
	}

	public List<String> stringToListCli(String defaultRtl433Cli) {
		List<String> lst = new ArrayList<String>();
		defaultRtl433Cli = defaultRtl433Cli.replaceAll("  ", " ");
		String[] rtlStrArray = defaultRtl433Cli.split(" ");
		for (String cliPart : rtlStrArray) {
			lst.add(cliPart);
		}
		return lst;
	}

	public String getAbsoluteRunningPath() {
		Path currentRelativePath = Paths.get("");
		return currentRelativePath.toAbsolutePath().toString();
	}

	public String getAbsoluteMainJarPath() throws URISyntaxException {
		return new File(App.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
	}
	
	public String getCurrentPid() {
		String ret = null;
		String jvmName = ManagementFactory.getRuntimeMXBean().getName();
	    int index = jvmName.indexOf('@');
	    ret = Long.toString(Long.parseLong(jvmName.substring(0, index)));
	   
	    
	    return ret;
	}

}
