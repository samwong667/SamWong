/*
 * Copyright (c) 2012-2013 NetEase, Inc. and other contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.taobao.hummingbird.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Properties;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;


import com.taobao.hummingbird.R;
import com.taobao.hummingbird.activity.MainPageActivity;
import com.taobao.hummingbird.utils.CpuInfo;
import com.taobao.hummingbird.utils.CurrentInfo;
import com.taobao.hummingbird.utils.EncryptData;
import com.taobao.hummingbird.utils.MailSender;
import com.taobao.hummingbird.utils.MemoryInfo;
import com.taobao.hummingbird.utils.MyApplication;



/**
 * Service running in background
 * 
 * @author 
 */
public class HummingbirdService extends Service {

	private final static String LOG_TAG = "Hummingbird-" + HummingbirdService.class.getSimpleName();

	private WindowManager windowManager = null;
	private WindowManager.LayoutParams wmParams = null;
	private View viFloatingWindow;
	private float mTouchStartX;
	private float mTouchStartY;
	private float startX;
	private float startY;
	private float x;
	private float y;
	private TextView txtTotalMem;
	private TextView txtUnusedMem;
	private TextView txtTraffic;
	private Button btnStop;
	private Button btnWifi;
	private int delaytime;
	private DecimalFormat fomart;
	private MemoryInfo memoryInfo;
	private WifiManager wifiManager;
	private Handler handler = new Handler();
	private CpuInfo cpuInfo;
	private String time;
	private boolean isFloating;
	private String processName, packageName, settingTempFile, startActivity;
	private int pid, uid;
	private boolean isServiceStop = false;
	private String sender, password, recipients, smtp;
	private String[] receivers;
	private EncryptData des;

	public static BufferedWriter bw;
	public static FileOutputStream out;
	public static OutputStreamWriter osw;
	public static String resultFilePath;
	public static boolean isStop = false;

	private String totalBatt;
	private String temperature;
	private String voltage;
	private CurrentInfo currentInfo;
	private BatteryInfoBroadcastReceiver batteryBroadcast = null;

	// get start time
	private static final int MAX_START_TIME_COUNT = 5;
	private static final String START_TIME = "#startTime";
	private int getStartTimeCount = 0;
	private boolean isGetStartTime = true;
	private String startTime = "";

	@Override
	public void onCreate() {
		Log.i(LOG_TAG, "onCreate");
		super.onCreate();
		isServiceStop = false;
		isStop = false;
		memoryInfo = new MemoryInfo();
		fomart = new DecimalFormat();
		fomart.setMaximumFractionDigits(2);
		fomart.setMinimumFractionDigits(0);
		des = new EncryptData("hummingbird");
		currentInfo = new CurrentInfo();
		batteryBroadcast = new BatteryInfoBroadcastReceiver();
		registerReceiver(batteryBroadcast, new IntentFilter("android.intent.action.BATTERY_CHANGED"));
	}

	/**
	 * 电池信息监控监听器
	 * 
	 * @author 
	 * 
	 */
	public class BatteryInfoBroadcastReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {

			if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
				int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);

				int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
				totalBatt = String.valueOf(level * 100 / scale) + "%";

				voltage = String.valueOf(intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) * 1.0 / 1000);

				temperature = String.valueOf(intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) * 1.0 / 10);
			}

		}

	}

	@Override
	public void onStart(Intent intent, int startId) {
		Log.i(LOG_TAG, "onStart");
		PendingIntent contentIntent = PendingIntent.getActivity(getBaseContext(), 0, new Intent(this, MainPageActivity.class), 0);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
		builder.setContentIntent(contentIntent).setSmallIcon(R.drawable.icon).setWhen(System.currentTimeMillis()).setAutoCancel(true)
				.setContentTitle("Hummingbird");
		startForeground(startId, builder.build());

		pid = intent.getExtras().getInt("pid");
		uid = intent.getExtras().getInt("uid");
		processName = intent.getExtras().getString("processName");
		packageName = intent.getExtras().getString("packageName");
		settingTempFile = intent.getExtras().getString("settingTempFile");
		startActivity = intent.getExtras().getString("startActivity");

		cpuInfo = new CpuInfo(getBaseContext(), pid, Integer.toString(uid));
		readSettingInfo(intent);
		delaytime = Integer.parseInt(time) * 1000;
		if (isFloating) {
			viFloatingWindow = LayoutInflater.from(this).inflate(R.layout.floating, null);
			txtUnusedMem = (TextView) viFloatingWindow.findViewById(R.id.memunused);
			txtTotalMem = (TextView) viFloatingWindow.findViewById(R.id.memtotal);
			txtTraffic = (TextView) viFloatingWindow.findViewById(R.id.traffic);
			btnWifi = (Button) viFloatingWindow.findViewById(R.id.wifi);

			wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
			if (wifiManager.isWifiEnabled()) {
				btnWifi.setText(R.string.closewifi);
			} else {
				btnWifi.setText(R.string.openwifi);
			}
			txtUnusedMem.setText("计算中,请稍后...");
			txtUnusedMem.setTextColor(android.graphics.Color.RED);
			txtTotalMem.setTextColor(android.graphics.Color.RED);
			txtTraffic.setTextColor(android.graphics.Color.RED);
			btnStop = (Button) viFloatingWindow.findViewById(R.id.stop);
			btnStop.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					Intent intent = new Intent();
					intent.putExtra("isServiceStop", true);
					intent.setAction("com.taobao.action.hummingbirdService");
					sendBroadcast(intent);
					// isServiceStop = true;
					Toast.makeText(HummingbirdService.this, "测试结果文件：" + resultFilePath, Toast.LENGTH_LONG).show();
					stopSelf();
				}
			});
			createFloatingWindow();
		}
		createResultCsv();
		handler.postDelayed(task, 1000);
	}

	/**
	 * read configuration file.
	 * 
	 * @throws IOException
	 */
	private void readSettingInfo(Intent intent) {
		try {
			Properties properties = new Properties();
			properties.load(new FileInputStream(settingTempFile));
			String interval = properties.getProperty("interval").trim();
			isFloating = "true".equals(properties.getProperty("isfloat").trim()) ? true : false;
			sender = properties.getProperty("sender").trim();
			password = properties.getProperty("password").trim();
			recipients = properties.getProperty("recipients").trim();
			time = "".equals(interval) ? "5" : interval;
			recipients = properties.getProperty("recipients");
			receivers = recipients.split("\\s+");
			smtp = properties.getProperty("smtp");
		} catch (IOException e) {
			time = "5";
			isFloating = true;
			Log.e(LOG_TAG, e.getMessage());
		}
	}

	/**
	 * write the test result to csv format report.
	 */
	private void createResultCsv() {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
		String mDateTime;
		if ((Build.MODEL.equals("sdk")) || (Build.MODEL.equals("google_sdk")))
			mDateTime = formatter.format(cal.getTime().getTime() + 8 * 60 * 60 * 1000);
		else
			mDateTime = formatter.format(cal.getTime().getTime());

		if (android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED)) {
			resultFilePath = android.os.Environment.getExternalStorageDirectory() + File.separator + "Hummingbird_TestResult_" + mDateTime + ".csv";
		} else {
			resultFilePath = getBaseContext().getFilesDir().getPath() + File.separator + "Hummingbird_TestResult_" + mDateTime + ".csv";
		}
		try {
			File resultFile = new File(resultFilePath);
			resultFile.createNewFile();
			out = new FileOutputStream(resultFile);
			osw = new OutputStreamWriter(out, "UTF-8");
			bw = new BufferedWriter(osw);
			long totalMemorySize = memoryInfo.getTotalMemory();
			String totalMemory = fomart.format((double) totalMemorySize / 1024);
			bw.write("指定应用的CPU内存监控情况\r\n" + "应用包名：," + packageName + "\r\n" + "应用名称: ," + processName + "\r\n" + "应用PID: ," + pid + "\r\n"
					+ "机器内存大小(MB)：," + totalMemory + "MB\r\n" + "机器CPU型号：," + cpuInfo.getCpuName() + "\r\n" + "机器android系统版本：,"
					+ memoryInfo.getSDKVersion() + "\r\n" + "手机型号：," + memoryInfo.getPhoneType() + "\r\n" + "UID：," + uid + "\r\n" + START_TIME);
			bw.write("时间" + "," + "应用占用内存PSS(MB)" + "," + "应用占用内存比(%)" + "," + " 机器剩余内存(MB)" + "," + "应用占用CPU率(%)" + "," + "CPU总使用率(%)" + ","
					+ "流量(KB)" + "," + "电量(%)" + "," + "电流(mA)" + "," + "温度(C)" + "," + "电压(V)" + "\r\n");
		} catch (IOException e) {
			Log.e(LOG_TAG, e.getMessage());
		}
	}

	/**
	 * create a floating window to show real-time data.
	 */
	private void createFloatingWindow() {
		SharedPreferences shared = getSharedPreferences("float_flag", Activity.MODE_PRIVATE);
		SharedPreferences.Editor editor = shared.edit();
		editor.putInt("float", 1);
		editor.commit();
		windowManager = (WindowManager) getApplicationContext().getSystemService("window");
		wmParams = ((MyApplication) getApplication()).getMywmParams();
		wmParams.type = 2002;
		wmParams.flags |= 8;
		wmParams.gravity = Gravity.LEFT | Gravity.TOP;
		wmParams.x = 0;
		wmParams.y = 0;
		wmParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
		wmParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
		wmParams.format = 1;
		windowManager.addView(viFloatingWindow, wmParams);
		viFloatingWindow.setOnTouchListener(new OnTouchListener() {
			public boolean onTouch(View v, MotionEvent event) {
				x = event.getRawX();
				y = event.getRawY() - 25;
				switch (event.getAction()) {
				case MotionEvent.ACTION_DOWN:
					startX = x;
					startY = y;
					mTouchStartX = event.getX();
					mTouchStartY = event.getY();
					Log.d("startP", "startX" + mTouchStartX + "====startY" + mTouchStartY);
					break;
				case MotionEvent.ACTION_MOVE:
					updateViewPosition();
					break;

				case MotionEvent.ACTION_UP:
					updateViewPosition();
					// showImg();
					mTouchStartX = mTouchStartY = 0;
					break;
				}
				return true;
			}
		});

		btnWifi.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				try {
					btnWifi = (Button) viFloatingWindow.findViewById(R.id.wifi);
					String buttonText = (String) btnWifi.getText();
					String wifiText = getResources().getString(R.string.openwifi);
					if (buttonText.equals(wifiText)) {
						wifiManager.setWifiEnabled(true);
						btnWifi.setText(R.string.closewifi);
					} else {
						wifiManager.setWifiEnabled(false);
						btnWifi.setText(R.string.openwifi);
					}
				} catch (Exception e) {
					Toast.makeText(viFloatingWindow.getContext(), "操作wifi失败", Toast.LENGTH_LONG).show();
					Log.e(LOG_TAG, e.toString());
				}
			}
		});
	}

	// /**
	// * show the image.
	// */
	// private void showImg() {
	// if (Math.abs(x - startX) < 1.5 && Math.abs(y - startY) < 1.5 &&
	// !btnStop.isShown()) {
	// btnStop.setVisibility(View.VISIBLE);
	// } else if (btnStop.isShown()) {
	// btnStop.setVisibility(View.GONE);
	// }
	// }

	private Runnable task = new Runnable() {

		public void run() {
			if (!isServiceStop) {
				dataRefresh();
				handler.postDelayed(this, delaytime);
				if (isFloating) {
					windowManager.updateViewLayout(viFloatingWindow, wmParams);
				}
				// get app start time from logcat on every task running
				getStartTimeFromLogcat();
			} else {
				Intent intent = new Intent();
				intent.putExtra("isServiceStop", true);
				intent.setAction("com.netease.action.HummingbirdService");
				sendBroadcast(intent);
				stopSelf();
			}
		}
	};

	/**
	 * Try to get start time from logcat.
	 */
	private void getStartTimeFromLogcat() {
		if (!isGetStartTime || getStartTimeCount >= MAX_START_TIME_COUNT) {
			return;
		}
		try {
			// filter logcat by Tag:ActivityManager and Level:Info
			String logcatCommand = "logcat -v time -d ActivityManager:I *:S";
			Process process = Runtime.getRuntime().exec(logcatCommand);
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			StringBuilder strBuilder = new StringBuilder();
			String line = "";

			while ((line = bufferedReader.readLine()) != null) {
				strBuilder.append(line);
				strBuilder.append("\r\n");
				String regex = ".*Displayed.*" + startActivity + ".*\\+(.*)ms.*";
				Log.d("my logs", regex);
				if (line.matches(regex)) {
					Log.w("my logs", line);
					if (line.contains("total")) {
						line = line.substring(0, line.indexOf("total"));
					}
					startTime = line.substring(line.lastIndexOf("+") + 1, line.lastIndexOf("ms") + 2);
					Toast.makeText(HummingbirdService.this, "启动时间：" + startTime, Toast.LENGTH_LONG).show();
					isGetStartTime = false;
					break;
				}
			}
			getStartTimeCount++;
			// Log.w("my logs", "Start Time Log：" + strBuilder.toString());
			Log.w(LOG_TAG, "getStartCount：" + getStartTimeCount);
		} catch (IOException e) {
			Log.d(LOG_TAG, e.getMessage());
		}
	}

	/**
	 * refresh the performance data showing in floating window.
	 * 
	 * @throws FileNotFoundException
	 * 
	 * @throws IOException
	 */
	private void dataRefresh() {
		int pidMemory = memoryInfo.getPidMemorySize(pid, getBaseContext());
		long freeMemory = memoryInfo.getFreeMemorySize(getBaseContext());
		String freeMemoryKb = fomart.format((double) freeMemory / 1024);
		String processMemory = fomart.format((double) pidMemory / 1024);
		String currentBatt = String.valueOf(currentInfo.getCurrentValue());
		// 异常数据过滤
		Log.d("my logs-before", currentBatt);
		try {
			if (Math.abs(Double.parseDouble(currentBatt)) >= 500) {
				currentBatt = "N/A";
			} else {
				currentBatt = currentBatt + "mA";
			}
		} catch (Exception e) {
			currentBatt = "N/A";
		}
		Log.d("my logs-after", currentBatt);
		ArrayList<String> processInfo = cpuInfo.getCpuRatioInfo(totalBatt, currentBatt, temperature, voltage);
		if (isFloating) {
			String processCpuRatio = "0";
			String totalCpuRatio = "0";
			String trafficSize = "0";
			int tempTraffic = 0;
			double trafficMb = 0;
			boolean isMb = false;
			if (!processInfo.isEmpty()) {
				processCpuRatio = processInfo.get(0);
				totalCpuRatio = processInfo.get(1);
				trafficSize = processInfo.get(2);
				if (!("".equals(trafficSize)) && !("-1".equals(trafficSize))) {
					tempTraffic = Integer.parseInt(trafficSize);
					if (tempTraffic > 1024) {
						isMb = true;
						trafficMb = (double) tempTraffic / 1024;
					}
				}
				// 如果cpu使用率存在且都不小于0，则输出
				if (processCpuRatio != null && totalCpuRatio != null) {
					txtUnusedMem.setText("应用/剩余内存:" + processMemory + "/" + freeMemoryKb + "MB");
					txtTotalMem.setText("应用/总体CPU:" + processCpuRatio + "%/" + totalCpuRatio + "%");
					String batt = "电流:" + currentBatt;
					if ("-1".equals(trafficSize)) {
						txtTraffic.setText(batt + ",流量:N/A");
					} else if (isMb)
						txtTraffic.setText(batt + ",流量:" + fomart.format(trafficMb) + "MB");
					else
						txtTraffic.setText(batt + ",流量:" + trafficSize + "KB");
				}
				// 当内存为0切cpu使用率为0时则是被测应用退出
				if ("0".equals(processMemory) && "0.00".equals(processCpuRatio)) {
					closeOpenedStream();
					isServiceStop = true;
					return;
				}
			}

		}
	}

	/**
	 * update the position of floating window.
	 */
	private void updateViewPosition() {
		wmParams.x = (int) (x - mTouchStartX);
		wmParams.y = (int) (y - mTouchStartY);
		windowManager.updateViewLayout(viFloatingWindow, wmParams);
	}

	/**
	 * close all opened stream.
	 */
	public static void closeOpenedStream() {
		try {
			if (bw != null) {
				bw.write("注释:已知部分不支持的机型可在此查阅://Todo \r\n");
				bw.write("电流:小于0是放电大于0是充电\r\n启动时间:为空是应用已启动或者未搜集到启动时间\r\n");
				bw.write("N/A表示不支持或者数据异常\r\n");
				bw.close();
			}
			if (osw != null)
				osw.close();
			if (out != null)
				out.close();
		} catch (Exception e) {
			Log.d(LOG_TAG, e.getMessage());
		}
	}

	@Override
	public void onDestroy() {
		Log.i(LOG_TAG, "onDestroy");
		if (windowManager != null)
			windowManager.removeView(viFloatingWindow);
		handler.removeCallbacks(task);
		closeOpenedStream();
		// replace the start time in file
		if (!"".equals(startTime)) {
			replaceFileString(resultFilePath, START_TIME, "启动时间:" + startTime + "\r\n");
		} else {
			replaceFileString(resultFilePath, START_TIME, "");
		}
		isStop = true;
		unregisterReceiver(batteryBroadcast);
		boolean isSendSuccessfully = false;
		try {
			isSendSuccessfully = MailSender.sendTextMail(sender, des.decrypt(password), smtp, "Hummingbird Performance Test Report", "see attachment",
					resultFilePath, receivers);
		} catch (Exception e) {
			isSendSuccessfully = false;
		}
		if (isSendSuccessfully) {
			Toast.makeText(this, "测试结果报表已发送至邮箱:" + recipients, Toast.LENGTH_LONG).show();
		} else {
			Toast.makeText(this, "测试结果未成功发送至邮箱，结果保存在:" + HummingbirdService.resultFilePath, Toast.LENGTH_LONG).show();
		}
		super.onDestroy();
		stopForeground(true);
	}

	/**
	 * Replaces all matches for replaceType within this replaceString in file on
	 * the filePath
	 * 
	 * @param filePath
	 * @param replaceType
	 * @param replaceString
	 */
	private void replaceFileString(String filePath, String replaceType, String replaceString) {
		try {
			File file = new File(filePath);
			BufferedReader reader = new BufferedReader(new FileReader(file));
			String line = "", oldtext = "";
			while ((line = reader.readLine()) != null) {
				oldtext += line + "\r\n";
			}
			reader.close();
			// replace a word in a file
			String newtext = oldtext.replaceAll(replaceType, replaceString);
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath), "UTF-8"));
			writer.write(newtext);
			writer.close();
		} catch (IOException e) {
			Log.d(LOG_TAG, e.getMessage());
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}