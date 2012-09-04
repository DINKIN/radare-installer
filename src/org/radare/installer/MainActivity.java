/*
radare2 installer for Android
(c) 2012 Pau Oliva Fora <pof[at]eslack[dot]org>
*/
package org.radare.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.DataOutputStream;

import java.util.zip.GZIPInputStream;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import org.radare.installer.R;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.*;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.Context;
import android.os.Build;

import android.content.Intent;
import android.net.Uri;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.ice.tar.*;
import com.stericson.RootTools.*;

public class MainActivity extends Activity {
	
	private TextView outputView;
	private Handler handler = new Handler();
	private Button remoteRunButton;
	private Button localRunButton;
	
	private Context context;

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		outputView = (TextView)findViewById(R.id.outputView);
		remoteRunButton = (Button)findViewById(R.id.remoteRunButton);
		remoteRunButton.setOnClickListener(onRemoteRunButtonClick);

		localRunButton = (Button)findViewById(R.id.localRunButton);
		localRunButton.setOnClickListener(onLocalRunButtonClick);

		RootTools.useRoot = false;
		if (RootTools.exists("/data/data/org.radare.installer/radare2/bin/radare2")) {
			localRunButton.setClickable(true);
		} else {
			localRunButton.setClickable(false);
		}

	}

	private OnClickListener onLocalRunButtonClick = new OnClickListener() {
		public void onClick(View v) {
			Thread thread = new Thread(new Runnable() {
				public void run() {
					if (isAppInstalled("jackpal.androidterm")) {
						try {
							Intent i = new Intent("jackpal.androidterm.RUN_SCRIPT");
							i.addCategory(Intent.CATEGORY_DEFAULT);
							i.putExtra("jackpal.androidterm.iInitialCommand", "/data/data/org.radare.installer/radare2/bin/radare2 /system/bin/ls");
							startActivity(i);
						} catch (Exception e) {
							output ("Radare2 installer needs to be reinstalled to be able to launch the terminal emulator\nPlease uninstall radare2 installer, and reinstall again.");
						}
					} else {
						Intent i = new Intent(Intent.ACTION_VIEW); 
						i.setData(Uri.parse("market://details?id=jackpal.androidterm")); 
						startActivity(i);
					}
				}
			});
			thread.start();
		}
	};

	private OnClickListener onRemoteRunButtonClick = new OnClickListener() {
		public void onClick(View v) {

			//RootTools.debugMode = true;

			// disable button click if it has been clicked once
			remoteRunButton.setClickable(false);
			outputView.setText("");

			final String localPath = "/data/data/org.radare.installer/radare2-android.tar.gz";
			final CheckBox checkBox = (CheckBox) findViewById(R.id.checkbox);
			final CheckBox checkHg = (CheckBox) findViewById(R.id.checkhg);

			Thread thread = new Thread(new Runnable() {
				public void run() {

					String url;
					String hg;
					String output;
					String arch = "arm";
					String cpuabi = Build.CPU_ABI;

					if (cpuabi.matches(".*mips.*")) arch="mips";
					if (cpuabi.matches(".*x86.*")) arch="x86";
					if (cpuabi.matches(".*arm.*")) arch="arm";
					
					output ("Detected CPU: " + cpuabi + "\n");

					if (checkHg.isChecked()) {
						output("Download: unstable/development version from nightly build.\nNote: this version can be broken!\n");
						hg = "unstable";
					} else {
						output("Download: stable version\n");
						hg = "stable";
					}

					url = "http://radare.org/get/pkg/android/" + arch + "/" + hg;

					/* fix broken stable URL in radare2 0.9 */
					if (cpuabi.matches(".*arm.*")) {
						if (!checkHg.isChecked()) url = "http://x90.es/radare2tar";
					}

					long space = 0;
					if (checkBox.isChecked()) {
						// getSpace needs root, only try it the symlinks checkbox has been checked
						space = (RootTools.getSpace("/data") / 1000);
						output("Free space in /data partition: "+ space +" MB\n");
					}

					if (space <= 0) {
						output("Warning: could not check space in /data partition, installation can fail!\n");
					} else if (space < 15) {
						output("Warning: low space in /data partition, installation can fail!\n");
					}

					output("Downloading radare2-android... please wait\n");
					//output("URL: "+url+"\n");

					if (isInternetAvailable() == false) {
						output("\nCan't connect to download server. Check that internet connection is available.\n");
					} else {

						RootTools.useRoot = false;
						// remove old traces of previous r2 install
						exec("rm -r /data/data/org.radare.installer/radare2/");
						exec("rm -r /data/rata/org.radare.installer/files/");
						exec("rm /data/data/org.radare.installer/radare2-android.tar");
						exec("rm /data/data/org.radare.installer/radare2-android.tar.gz");

						// real download
						download(url, localPath);
						output("Installing radare2... please wait\n");

						try {
							unTarGz(localPath, "/data/data/org.radare.installer/");
						} catch (Exception e) {
							e.printStackTrace();
						}

						// make sure we delete temporary files
						exec("rm /data/data/org.radare.installer/radare2-android.tar");
						exec("rm /data/data/org.radare.installer/radare2-android.tar.gz");

						// make sure bin files are executable
						exec("chmod 755 /data/data/org.radare.installer/radare2/bin/*");
						exec("chmod 755 /data/data/org.radare.installer/radare2/bin/");
						exec("chmod 755 /data/data/org.radare.installer/radare2/");

						boolean symlinksCreated = false;
						if (checkBox.isChecked()) {

							boolean isRooted = false;
							isRooted = RootTools.isAccessGiven();

							if(!isRooted) {
								output("\nCould not create xbin symlinks, do you have root?\n");
							} else { // device is rooted

								RootTools.useRoot = true;

								output("\nCreating xbin symlinks...\n");
								RootTools.remount("/system", "rw");
								// remove old path
								exec("rm -r /data/local/radare2");
								// remove old symlinks in case they exist in old location
								exec("rm -r /system/xbin/radare2 /system/xbin/r2 /system/xbin/rabin2 /system/xbin/radiff2 /system/xbin/ragg2 /system/xbin/rahash2 /system/xbin/ranal2 /system/xbin/rarun2 /system/xbin/rasm2 /system/xbin/rax2 /system/xbin/rafind2 /system/xbin/ragg2-cc");

								if (RootTools.exists("/data/data/org.radare.installer/radare2/bin/radare2")) {

									// show output for the first link, in case there's any error with su
									output = exec("ln -s /data/data/org.radare.installer/radare2/bin/radare2 /system/xbin/radare2 2>&1");
									output(output);

									String file;
									File folder = new File("/data/data/org.radare.installer/radare2/bin/");
									File[] listOfFiles = folder.listFiles(); 
									for (int i = 0; i < listOfFiles.length; i++) {
										if (listOfFiles[i].isFile()) {
											file = listOfFiles[i].getName();
											exec("ln -s /data/data/org.radare.installer/radare2/bin/" + file + " /system/xbin/" + file);
											output("linking /system/xbin/" + file + "\n");
										}
									}
								}

								RootTools.remount("/system", "ro");
								if (RootTools.exists("/system/xbin/radare2")) {
									output("done\n");
									symlinksCreated = true;
								} else {
									output("\nFailed to create xbin symlinks\n");
									symlinksCreated = false;
								}

								RootTools.useRoot = false;
							}
						}

						RootTools.useRoot = false;
						if (!RootTools.exists("/data/data/org.radare.installer/radare2/bin/radare2")) {
							localRunButton.setClickable(false);
							output("\n\nsomething went wrong during installation :(\n");
						} else {
							localRunButton.setClickable(true);
							if (symlinksCreated == false) output("\nRadare2 is installed in:\n   /data/data/org.radare.installer/radare2/\n");
							output("\nTesting installation:\n\n$ radare2 -v\n");
							output = exec("/data/data/org.radare.installer/radare2/bin/radare2 -v");
							output(output);
						}
					}
					// enable button again
					remoteRunButton.setClickable(true);
				}
			});
			thread.start();
		}
	};

	private boolean isAppInstalled(String namespace) {
		try{
			ApplicationInfo info = getPackageManager().getApplicationInfo(namespace, 0 );
		return true;
		} catch( PackageManager.NameNotFoundException e ){
			return false;
		}
	}

	private String exec(String command) {
		final StringBuffer radare_output = new StringBuffer();
		Command command_out = new Command(0, command)
		{
        		@Override
        		public void output(int id, String line)
        		{
				radare_output.append(line);
        		}
		};
		try {
			RootTools.getShell(RootTools.useRoot).add(command_out).waitForFinish();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return radare_output.toString();
	}

	private void output(final String str) {
		Runnable proc = new Runnable() {
			public void run() {
				if (str!=null) outputView.append(str);
			}
		};
		handler.post(proc);
	}


	public static void unTarGz(final String zipPath, final String unZipPath) throws Exception {
		GZIPInputStream gzipInputStream = new GZIPInputStream(new FileInputStream(zipPath));

		//path and name of the tempoary tar file
		String tempDir = unZipPath.substring(0, unZipPath.lastIndexOf('/'));
		String tempFile = "radare-android.tar";
		String tempPath = tempDir + "/" + tempFile;

		//first we create the gunzipped tarball...
		OutputStream out = new FileOutputStream(tempPath);

		byte[] data = new byte[1024];
		int len;
		while ((len = gzipInputStream.read(data)) > 0) {
			out.write(data, 0, len);
		}

		gzipInputStream.close();
		out.close();

		//...then we use com.ice.tar to extract the tarball contents
		TarArchive tarArchive = new TarArchive(new FileInputStream(tempPath));
		tarArchive.extractContents(new File("/"));
		tarArchive.closeArchive();

		//remove the temporary gunzipped tar
		new File(tempPath).delete();
	}



	public final boolean isInternetAvailable(){
	// check if we are connected to the internet
		ConnectivityManager connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo info = connectivityManager.getActiveNetworkInfo();
		if(info == null)
		    return false;

		return connectivityManager.getActiveNetworkInfo().isConnected();
	}


	private void download(String urlStr, String localPath) {
		try {
			URL url = new URL(urlStr);
			HttpURLConnection urlconn = (HttpURLConnection)url.openConnection();
			urlconn.setRequestMethod("GET");
			urlconn.setInstanceFollowRedirects(true);
			urlconn.connect();
			InputStream in = urlconn.getInputStream();
			FileOutputStream out = new FileOutputStream(localPath);
			int read;
			byte[] buffer = new byte[4096];
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
			}
			out.close();
			in.close();
			urlconn.disconnect();
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
    
}
