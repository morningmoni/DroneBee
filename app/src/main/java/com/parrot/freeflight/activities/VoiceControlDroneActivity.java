/*
 * ControlDroneActivity
 * 
 * Created on: May 5, 2011
 * Author: Dmytro Baryskyy
 */

package com.parrot.freeflight.activities;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.SoundPool;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.GestureDetector.OnDoubleTapListener;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;

import com.aispeech.AIError;
import com.aispeech.AIResult;
import com.aispeech.IMergeRule;
import com.aispeech.common.Util;
import com.aispeech.export.engines.AILocalGrammarEngine;
import com.aispeech.export.engines.AIMixASREngine;
import com.aispeech.export.listeners.AIASRListener;
import com.parrot.freeflight.FreeFlightApplication;
import com.parrot.freeflight.R;
import com.parrot.freeflight.activities.base.ParrotActivity;
import com.parrot.freeflight.drone.DroneConfig;
import com.parrot.freeflight.drone.DroneConfig.EDroneVersion;
import com.parrot.freeflight.drone.NavData;
import com.parrot.freeflight.receivers.DroneBatteryChangedReceiver;
import com.parrot.freeflight.receivers.DroneBatteryChangedReceiverDelegate;
import com.parrot.freeflight.receivers.DroneCameraReadyActionReceiverDelegate;
import com.parrot.freeflight.receivers.DroneCameraReadyChangeReceiver;
import com.parrot.freeflight.receivers.DroneEmergencyChangeReceiver;
import com.parrot.freeflight.receivers.DroneEmergencyChangeReceiverDelegate;
import com.parrot.freeflight.receivers.DroneFlyingStateReceiver;
import com.parrot.freeflight.receivers.DroneFlyingStateReceiverDelegate;
import com.parrot.freeflight.receivers.DroneRecordReadyActionReceiverDelegate;
import com.parrot.freeflight.receivers.DroneRecordReadyChangeReceiver;
import com.parrot.freeflight.receivers.DroneVideoRecordStateReceiverDelegate;
import com.parrot.freeflight.receivers.DroneVideoRecordingStateReceiver;
import com.parrot.freeflight.receivers.WifiSignalStrengthChangedReceiver;
import com.parrot.freeflight.receivers.WifiSignalStrengthReceiverDelegate;
import com.parrot.freeflight.remotecontrollers.ButtonController;
import com.parrot.freeflight.remotecontrollers.ButtonDoubleClickController;
import com.parrot.freeflight.remotecontrollers.ButtonPressedController;
import com.parrot.freeflight.remotecontrollers.ButtonValueController;
import com.parrot.freeflight.remotecontrollers.ControlButtons;
import com.parrot.freeflight.remotecontrollers.ControlButtonsFactory;
import com.parrot.freeflight.sensors.DeviceOrientationChangeDelegate;
import com.parrot.freeflight.sensors.DeviceOrientationManager;
import com.parrot.freeflight.sensors.DeviceSensorManagerWrapper;
import com.parrot.freeflight.sensors.RemoteSensorManagerWrapper;
import com.parrot.freeflight.service.DroneControlService;
import com.parrot.freeflight.settings.ApplicationSettings;
import com.parrot.freeflight.settings.ApplicationSettings.ControlMode;
import com.parrot.freeflight.settings.ApplicationSettings.EAppSettingProperty;
import com.parrot.freeflight.transcodeservice.TranscodingService;
import com.parrot.freeflight.ui.SettingsDialogDelegate;
import com.parrot.freeflight.ui.VoiceHudViewController;
import com.parrot.freeflight.utils.FileUtils;
import com.parrot.freeflight.utils.NookUtils;
import com.parrot.freeflight.utils.SystemUtils;
import com.parrot.util.NetworkUtil;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedList;
import java.util.List;

@SuppressLint("NewApi")
public class VoiceControlDroneActivity
        extends ParrotActivity
        implements DeviceOrientationChangeDelegate, WifiSignalStrengthReceiverDelegate, DroneVideoRecordStateReceiverDelegate, DroneEmergencyChangeReceiverDelegate,
        DroneBatteryChangedReceiverDelegate, DroneFlyingStateReceiverDelegate, DroneCameraReadyActionReceiverDelegate, DroneRecordReadyActionReceiverDelegate, SettingsDialogDelegate
{
    private static final int LOW_DISK_SPACE_BYTES_LEFT = 1048576 * 20; //20 mebabytes
    private static final int WARNING_MESSAGE_DISMISS_TIME = 5000; // 5 seconds
    
    private static final String TAG = "ControlDroneActivity";
    private static final float ACCELERO_TRESHOLD = (float) Math.PI / 180.0f * 2.0f;

    private static final int PITCH = 1;
    private static final int ROLL = 2;


    private DroneControlService droneControlService;
    private ApplicationSettings settings;
    private SettingsDialog settingsDialog;

    //private JoystickListener rollPitchListener;
    //private JoystickListener gazYawListener;

    private VoiceHudViewController view;

    private boolean useSoftwareRendering;
   // private boolean forceCombinedControlMode;

    private int screenRotationIndex;

    private WifiSignalStrengthChangedReceiver wifiSignalReceiver;
    private DroneVideoRecordingStateReceiver videoRecordingStateReceiver;
    private DroneEmergencyChangeReceiver droneEmergencyReceiver;
    private DroneBatteryChangedReceiver droneBatteryReceiver;
    private DroneFlyingStateReceiver droneFlyingStateReceiver;
    private DroneCameraReadyChangeReceiver droneCameraReadyChangedReceiver;
    private DroneRecordReadyChangeReceiver droneRecordReadyChangeReceiver;

    private SoundPool soundPool;
    private int batterySoundId;
    private int effectsStreamId;

    private boolean combinedYawEnabled;
    private boolean acceleroEnabled;
    private boolean magnetoEnabled;
    private boolean magnetoAvailable;
    private boolean controlLinkAvailable;

    private boolean pauseVideoWhenOnSettings;

    private DeviceOrientationManager deviceOrientationManager;

    private float pitchBase;
    private float rollBase;
    private boolean running;

    private boolean flying;
    private boolean recording;
    private boolean cameraReady;
    private boolean prevRecording;
    private boolean rightJoyPressed;
    private boolean leftJoyPressed;
    private boolean isGoogleTV;

    private List<ButtonController> buttonControllers;

    private ServiceConnection mConnection = new ServiceConnection()
    {

        public void onServiceConnected(ComponentName name, IBinder service)
        {
            droneControlService = ((DroneControlService.LocalBinder) service).getService();
            onDroneServiceConnected();
        }

        public void onServiceDisconnected(ComponentName name)
        {
            droneControlService = null;
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        if (isFinishing()) {
            return;
        }
        
        settings = getSettings();

        this.isGoogleTV = SystemUtils.isGoogleTV(this);

        // TODO google TV requires specific sensor manager and device rotation
        if (this.isGoogleTV) {
            this.applyHandDependendTVControllers();
            deviceOrientationManager = new DeviceOrientationManager(new RemoteSensorManagerWrapper(this), this);
        } else {
            screenRotationIndex = getWindow().getWindowManager().getDefaultDisplay().getRotation();
            deviceOrientationManager = new DeviceOrientationManager(new DeviceSensorManagerWrapper(this), this);
        }
        
        deviceOrientationManager.onCreate();

        bindService(new Intent(this, DroneControlService.class), mConnection, Context.BIND_AUTO_CREATE);

        Bundle bundle = getIntent().getExtras();

        if (bundle != null) {
            useSoftwareRendering = bundle.getBoolean("USE_SOFTWARE_RENDERING");
//            forceCombinedControlMode = bundle.getBoolean("FORCE_COMBINED_CONTROL_MODE");
        } else {
            useSoftwareRendering = false;
//            forceCombinedControlMode = false;
        }

        pauseVideoWhenOnSettings = getResources().getBoolean(R.bool.settings_pause_video_when_opened);

        combinedYawEnabled = true;
        acceleroEnabled = false;
        running = false;

        //initRegularJoystics();

        view = new VoiceHudViewController(this, useSoftwareRendering);

        wifiSignalReceiver = new WifiSignalStrengthChangedReceiver(this);
        videoRecordingStateReceiver = new DroneVideoRecordingStateReceiver(this);
        droneEmergencyReceiver = new DroneEmergencyChangeReceiver(this);
        droneBatteryReceiver = new DroneBatteryChangedReceiver(this);
        droneFlyingStateReceiver = new DroneFlyingStateReceiver(this);
        droneCameraReadyChangedReceiver = new DroneCameraReadyChangeReceiver(this);
        droneRecordReadyChangeReceiver = new DroneRecordReadyChangeReceiver(this);

        soundPool = new SoundPool(2, AudioManager.STREAM_MUSIC, 0);
        batterySoundId = soundPool.load(this, R.raw.battery, 1);

        if (!deviceOrientationManager.isAcceleroAvailable()) {
            settings.setControlMode(ControlMode.NORMAL_MODE);
        }
        
        settings.setFirstLaunch(false);
        
//        view.setCameraButtonEnabled(false);
        view.setRecordButtonEnabled(false);


    }
    
    private void applyHandDependendTVControllers()
    {
        if (settings.isLeftHanded()) {
            screenRotationIndex = Surface.ROTATION_90;          
            initGoogleTVControllers(ControlButtonsFactory.getLeftHandedControls());
        } else {
            screenRotationIndex = Surface.ROTATION_270;  
            initGoogleTVControllers(ControlButtonsFactory.getRightHandedControls());
        }
    }

//    private void initRegularJoystics()
//    {
//        rollPitchListener = new JoystickListener()
//        {
//
//            public void onChanged(JoystickBase joy, float x, float y)
//            {
//                if (droneControlService != null && acceleroEnabled == false && running == true) {
//                    droneControlService.setRoll(x);
//                    droneControlService.setPitch(-y);
//                }
//            }
//
//            @Override
//            public void onPressed(JoystickBase joy)
//            {
//                leftJoyPressed = true;
//
//                if (droneControlService != null) {
//                    droneControlService.setProgressiveCommandEnabled(true);
//
//                    if (combinedYawEnabled && rightJoyPressed) {
//                        droneControlService.setProgressiveCommandCombinedYawEnabled(true);
//                    } else {
//                        droneControlService.setProgressiveCommandCombinedYawEnabled(false);
//                    }
//                }
//
//                running = true;
//            }
//
//            @Override
//            public void onReleased(JoystickBase joy)
//            {
//                leftJoyPressed = false;
//
//                if (droneControlService != null) {
//                    droneControlService.setProgressiveCommandEnabled(false);
//
//                    if (combinedYawEnabled) {
//                        droneControlService.setProgressiveCommandCombinedYawEnabled(false);
//                    }
//                }
//
//                running = false;
//            }
//        };
//
//        gazYawListener = new JoystickListener()
//        {
//
//            public void onChanged(JoystickBase joy, float x, float y)
//            {
//                if (droneControlService != null) {
//                    droneControlService.setGaz(y);
//                    droneControlService.setYaw(x);
//                }
//            }
//
//            @Override
//            public void onPressed(JoystickBase joy)
//            {
//                rightJoyPressed = true;
//
//                if (droneControlService != null) {
//                    if (combinedYawEnabled && leftJoyPressed) {
//                        droneControlService.setProgressiveCommandCombinedYawEnabled(true);
//                    } else {
//                        droneControlService.setProgressiveCommandCombinedYawEnabled(false);
//                    }
//                }
//            }
//
//            @Override
//            public void onReleased(JoystickBase joy)
//            {
//                rightJoyPressed = false;
//
//                if (droneControlService != null && combinedYawEnabled) {
//                    droneControlService.setProgressiveCommandCombinedYawEnabled(false);
//                }
//            }
//        };
//    }

    class RecordThread extends Thread
    {
        private long startTime = 0;
        private long endTime = 0;
        private boolean takePhoto = true;
        @Override
        public synchronized void run()
        {
            Log.i("tttt", "RecordThread start.");
            while (takePhoto) {
                if (startTime == 0) {
                    onRecord();
                    startTime = System.currentTimeMillis(); //毫秒
                }
                endTime = System.currentTimeMillis();
                long dt = (endTime - startTime);

                if (dt > 1500) {
                    onRecord();
                    GetandSaveCurrentImage();
                    takePhoto = false;
                    /*try {
                        Thread.sleep(1000);  //保证视频已经写入
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }*/
                    //while (!faceDetectFinished); //等待人脸图片生成
                    Log.i("tttt", "face finish.");
                }
            }
        }
    }

    private File GetNewestFile(String Path, String Extension)
    {
        try {
            Thread.sleep(500);  //保证视频已经写入
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        File result = null;
        File[] files =new File(Path).listFiles();

        long modifiedTime = Long.MIN_VALUE;

        for (int i =0; i < files.length; i++) {
            File f = files[i];
            //Log.i("tttt",f.getPath());
            if (f!=null && f.isFile()) {
                //Log.i("tttt", "optional:" + f.getPath());
                if (f.getPath().substring(f.getPath().length() - Extension.length()).equals(Extension)) { //判断扩展名{
                    if (f.lastModified() > modifiedTime) {
                        result = f;
                        modifiedTime = f.lastModified();
                    }
                }
            }
        }
        //Log.i("tttta", "final" + result.getPath());
        return result;

    }

    private void GetandSaveCurrentImage() {
        File dcimDir = FileUtils.getMediaFolder(getApplicationContext());
        String SavePath = dcimDir.getAbsolutePath();
        //Log.i("tttt", "dir=" + SavePath);
        File videoFile = GetNewestFile(SavePath, "mp4");
        Log.i("tttt", "video:"+videoFile.getPath());
        if (videoFile == null)
            Log.i("tttt","got no video file.");
        MediaMetadataRetriever media = new MediaMetadataRetriever();
        try {
            String s = videoFile.getPath();
            String filepath = s;
            filepath=filepath.replace("mp4","png");
            Log.i("ttt",filepath);
            File picFile = new File(filepath);
            //boolean tt = picFile.exists();
            //如果没有png
            if(!picFile.exists()) {
                //Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
                //Log.i("tttt", s);
                media.setDataSource(s);
                Bitmap bitmap = media.getFrameAtTime();
                if (bitmap != null) {
                    File path = new File(SavePath);
                    if (!path.exists()) {
                        path.mkdirs();
                    }
                    if (!picFile.exists()) {
                        picFile.createNewFile();
                    }
                    FileOutputStream fos = null;
                    fos = new FileOutputStream(picFile);
                    if (null != fos) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
                        fos.flush();
                        fos.close();
                    }
                }
                if (videoFile != null)
                    videoFile.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initGoogleTVControllers(final ControlButtons buttons)
    {

        this.buttonControllers = new LinkedList<ButtonController>();
        this.buttonControllers.add(new ButtonValueController(buttons.getButtonCode(ControlButtons.BUTTON_UP), buttons.getButtonCode(ControlButtons.BUTTON_DOWN))
        {

            @Override
            public void onValueChanged(float theCurrentValue)
            {
                if (droneControlService != null) {
                    droneControlService.setGaz(theCurrentValue);
                }
            }
        });

        this.buttonControllers.add(new ButtonValueController(buttons.getButtonCode(ControlButtons.BUTTON_TURN_RIGHT), buttons.getButtonCode(ControlButtons.BUTTON_TURN_LEFT))
        {
            @Override
            public void onValueChanged(float theCurrentValue)
            {
                if (droneControlService != null) {
                    droneControlService.setYaw(theCurrentValue);
                }
            }
        });

        this.buttonControllers.add(new ButtonValueController(buttons.getButtonCode(ControlButtons.BUTTON_PITCH_LEFT), buttons.getButtonCode(ControlButtons.BUTTON_PITCH_RIGHT))
        {
            @Override
            public void onValueChanged(float theCurrentValue)
            {
                if (droneControlService != null && acceleroEnabled == false && running) {
                    droneControlService.setPitch(theCurrentValue);
                }
            }
        });

        this.buttonControllers.add(new ButtonValueController(buttons.getButtonCode(ControlButtons.BUTTON_ROLL_FORWARD), buttons.getButtonCode(ControlButtons.BUTTON_ROLL_BACKWARD))
        {
            @Override
            public void onValueChanged(float theCurrentValue)
            {
                if (droneControlService != null && acceleroEnabled == false && running) {
                    droneControlService.setPitch(-theCurrentValue);
                }
            }
        });

        this.buttonControllers.add(new ButtonPressedController(buttons.getButtonCode(ControlButtons.BUTTON_ACCELEROMETR))
        {

            @Override
            public void onButtonReleased()
            {
                leftJoyPressed = false;

                if (droneControlService != null) {
                    droneControlService.setProgressiveCommandEnabled(false);

                    if (combinedYawEnabled) {
                        droneControlService.setProgressiveCommandCombinedYawEnabled(false);
                    }
                }

                running = false;
            }

            @Override
            public void onButtonPressed()
            {
                leftJoyPressed = true;

                if (droneControlService != null) {
                    droneControlService.setProgressiveCommandEnabled(true);

                    if (combinedYawEnabled && rightJoyPressed) {
                        droneControlService.setProgressiveCommandCombinedYawEnabled(true);
                    } else {
                        droneControlService.setProgressiveCommandCombinedYawEnabled(false);
                    }
                }

                running = true;
            }
        });

        this.buttonControllers.add(new ButtonDoubleClickController(buttons.getButtonCode(ControlButtons.BUTTON_SALTO))
        {

            @Override
            public void onButtonDoubleClicked()
            {
                if (settings.isFlipEnabled()) {
                    droneControlService.doLeftFlip();
                }
            }
        });

        this.buttonControllers.add(new ButtonPressedController(buttons.getButtonCode(ControlButtons.BUTTON_TAKE_OFF))
        {

            @Override
            public void onButtonReleased()
            {
                droneControlService.triggerTakeOff();
            }

            @Override
            public void onButtonPressed()
            {
            }
        });

        this.buttonControllers.add(new ButtonPressedController(buttons.getButtonCode(ControlButtons.BUTTON_EMERGENCY))
        {

            @Override
            public void onButtonReleased()
            {
                droneControlService.triggerEmergency();
            }

            @Override
            public void onButtonPressed()
            {
            }
        });

        this.buttonControllers.add(new ButtonPressedController(buttons.getButtonCode(ControlButtons.BUTTON_CAMERA))
        {

            @Override
            public void onButtonReleased()
            {
                droneControlService.switchCamera();
            }

            @Override
            public void onButtonPressed()
            {
            }
        });

        this.buttonControllers.add(new ButtonPressedController(buttons.getButtonCode(ControlButtons.BUTTON_SETTINGS))
        {

            @Override
            public void onButtonReleased()
            {
                showSettingsDialog();
            }

            @Override
            public void onButtonPressed()
            {
            }
        });

        this.buttonControllers.add(new ButtonPressedController(buttons.getButtonCode(ControlButtons.BUTTON_PHOTO))
        {

            @Override
            public void onButtonReleased()
            {
                onTakePhoto();
            }

            @Override
            public void onButtonPressed()
            {
            }
        });

        this.buttonControllers.add(new ButtonPressedController(buttons.getButtonCode(ControlButtons.BUTTON_RECORD))
        {

            @Override
            public void onButtonReleased()
            {
                onRecord();
            }

            @Override
            public void onButtonPressed()
            {
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if (applyKeyEvent(event)) {
            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event)
    {
        if (applyKeyEvent(event)) {
            return true;
        } else {
            return super.onKeyUp(keyCode, event);
        }
    }

    private boolean applyKeyEvent(KeyEvent theEvent)
    {
        boolean result = false;
        if (this.buttonControllers != null) {
            for (ButtonController controller : this.buttonControllers) {
                if (controller.onKeyEvent(theEvent)) {
                    result = true;
                }
            }
        }
        return result;
    }

    /**
     * 设置识别按钮的状态
     *
     * @param state
     *            使能状态
     * @param text
     *            按钮文本
     */
    private String action;
    AIMixASREngine mAsrEngine;
    private void setAsrBtnState(final boolean state,final boolean b) {
        runOnUiThread(new Runnable() {

            @Override
            public void run() {
                view.setVoiceButtonEnabled(state);
                if (b)
                {
                    lock=0;
                }
                else
                {
                    lock=1;
                }
            }
        });
    }

    private void showInfo(final String str) {
        runOnUiThread(new Runnable() {

            @Override
            public void run() {
                Toast.makeText(VoiceControlDroneActivity.this,str,Toast.LENGTH_SHORT).show();
            }
        });
    }
    /**
     * 本地识别引擎回调接口，用以接收相关事件
     */
    public class AIASRListenerImpl implements AIASRListener {

        @Override
        public void onBeginningOfSpeech() {
            showInfo("检测到说话");

        }

        @Override
        public void onEndOfSpeech() {
            showInfo("检测到语音停止，开始识别...");
        }

        @Override
        public void onReadyForSpeech() {
            showInfo("请说话...");
        }

        @Override
        public void onRmsChanged(float rmsdB) {

        }

        @Override
        public void onError(AIError error) {
            showInfo("识别发生错误");
            setAsrBtnState(true,true);
        }


        @Override
        public void onResults(AIResult results) {
            Log.i(TAG, results.getResultObject().toString());
            try {
                JSONObject jsonObject=new JSONObject(results.getResultObject().toString());
                JSONObject result=jsonObject.getJSONObject("result");
                action=result.getJSONObject("post").getJSONObject("sem").get("action").toString();
                showInfo(action);
                if (droneControlService!=null)
                {
                    switch (action)
                    {
                        case "takeoff":
                            droneControlService.triggerTakeOff();
                            break;
                        case "landing":
                            droneControlService.triggerTakeOff();
                            break;
                        case "takephoto":
                            new RecordThread().start();
                            break;
                        case "record":
                            droneControlService.record();
                            break;
                        case "endrecord":
                            droneControlService.record();
                            break;
                        case "emergency":
                            droneControlService.triggerEmergency();
                            break;
                        case "moveforward":
                            droneControlService.moveForward(1);
                            break;
                        case "movebackward":
                            droneControlService.moveBackward(1);
                            break;
                        case "moveup":
                            droneControlService.moveUp(1);
                            break;
                        case "movedown":
                            droneControlService.moveDown(1);
                            break;
                        case "moveleft":
                            droneControlService.moveLeft(1);
                            break;
                        case "moveright":
                            droneControlService.moveRight(1);
                            break;
                        case "turnleft":
                            droneControlService.turnLeft(1);
                            break;
                        case "turnright":
                            droneControlService.turnRight(1);
                            break;
                        case "switchcamera":
                            droneControlService.switchCamera();
                            break;
                        case "stop":
                            droneControlService.stop();
                            break;
                        default:
                            break;
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
            setAsrBtnState(true,true);
        }

        @Override
        public void onInit(int status) {
            if (status == 0) {
                Log.i(TAG, "end of init asr engine");
                showInfo("本地识别引擎加载成功");
                view.setVoiceButtonEnabled(true);
                setAsrBtnState(true,true);
                if (NetworkUtil.isWifiConnected(VoiceControlDroneActivity.this)) {
                    if (mAsrEngine != null) {
                        mAsrEngine.setNetWorkState("WIFI");
                    }
                }
            } else {
                showInfo("本地识别引擎加载失败");
            }
        }

        @Override
        public void onRecorderReleased() {
            // showInfo("检测到录音机停止");
        }
    }

    private void initAsrEngine() {
        if (mAsrEngine != null) {
            mAsrEngine.destroy();
        }
        Log.i(TAG, "asr create");
        mAsrEngine = AIMixASREngine.createInstance();
        mAsrEngine.setResBin("ebnfr.aifar.0.0.1.bin");
        mAsrEngine.setNetBin(AILocalGrammarEngine.OUTPUT_NAME, true);

        //mAsrEngine.setWaitCloudTimeout(3000);
        mAsrEngine.setVadResource("vad.aihome.v0.3_20150901.bin");
        if (getExternalCacheDir() != null) {
            mAsrEngine.setTmpDir(getExternalCacheDir().getAbsolutePath());
            mAsrEngine.setUploadEnable(true);
            mAsrEngine.setUploadInterval(1000);
        }
        mAsrEngine.setServer("ws://s-test.api.aispeech.com:10000");
        mAsrEngine.setRes("aihome");
        mAsrEngine.setUseXbnfRec(true);
        mAsrEngine.setUsePinyin(true);
        mAsrEngine.setUseForceout(false);
        mAsrEngine.setAthThreshold(0.4f);//设置本地置信度阀值
        mAsrEngine.setIsRelyOnLocalConf(true);//是否开启依据本地置信度优先输出,如需添加例外
        mAsrEngine.setLocalBetterDomains(new String[] { "phone", "remind"});//设置本地擅长的领域范围
        mAsrEngine.setWaitCloudTimeout(3000);
        mAsrEngine.setPauseTime(1000);
        mAsrEngine.setUseConf(true);
        mAsrEngine.setNoSpeechTimeOut(0);
        mAsrEngine.setDeviceId(Util.getIMEI(this));
        // 自行设置合并规则:
        // 1. 如果无云端结果,则直接返回本地结果
        // 2. 如果有云端结果,当本地结果置信度大于阈值时,返回本地结果,否则返回云端结果
        mAsrEngine.setMergeRule(new IMergeRule() {

            @Override
            public AIResult mergeResult(AIResult localResult, AIResult cloudResult) {

                AIResult result = null;
                try {
                    if (cloudResult == null) {
                        // 为结果增加标记,以标示来源于云端还是本地
                        JSONObject localJsonObject = new JSONObject(localResult.getResultObject()
                                .toString());
                        localJsonObject.put("src", "native");

                        localResult.setResultObject(localJsonObject);
                        result = localResult;
                    } else {
                        JSONObject cloudJsonObject = new JSONObject(cloudResult.getResultObject()
                                .toString());
                        cloudJsonObject.put("src", "cloud");
                        cloudResult.setResultObject(cloudJsonObject);
                        result = cloudResult;
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                return result;

            }
        });
        mAsrEngine.init(this, new AIASRListenerImpl(), AppKey.APPKEY, AppKey.SECRETKEY);
        mAsrEngine.setUseCloud(false);//该方法必须在init之后
    }

    private int lock=0;

    private void initListeners()
    {
//        view.setSettingsButtonClickListener(new OnClickListener()
//        {
//            public void onClick(View v)
//            {
//                showSettingsDialog();
//            }
//        });
        initAsrEngine();

        view.setVoiceButtonCLickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (droneControlService != null) {
                    action="undefine";
                    if (lock==0) {
                        if (mAsrEngine != null) {
                            setAsrBtnState(true,false);
                            mAsrEngine.start();
                        }
                    } else {
                        if (mAsrEngine != null) {
                            setAsrBtnState(true,true);
                            mAsrEngine.stopRecording();
                        }
                    }
                }
            }
        });

        view.setBtnCameraSwitchClickListener(new OnClickListener()
        {

            public void onClick(View v)
            {
                if (droneControlService != null) {
                    droneControlService.switchCamera();
                }
            }
        });

        view.setBtnTakeOffClickListener(new OnClickListener()
        {

            public void onClick(View v)
            {
                if (droneControlService != null) {
                    droneControlService.triggerTakeOff();
                }
            }
        });

        view.setBtnEmergencyClickListener(new OnClickListener()
        {

            public void onClick(View v)
            {
                if (droneControlService != null) {
                    droneControlService.triggerEmergency();
                }
            }

        });

//        view.setBtnPhotoClickListener(new OnClickListener()
//        {
//            public void onClick(View v)
//            {
//                if (droneControlService != null) {
//                    onTakePhoto();
//                }
//            }
//        });

        view.setBtnRecordClickListener(new OnClickListener()
        {
            public void onClick(View v)
            {
                onRecord();
            }
        });

        view.setBtnBackClickListener(new OnClickListener()
        {
            public void onClick(View v)
            {
                finish();
            }
        });

        view.setDoubleTapClickListener(new OnDoubleTapListener()
        {

            public boolean onSingleTapConfirmed(MotionEvent e)
            {
                // Left unimplemented
                return false;
            }

            public boolean onDoubleTapEvent(MotionEvent e)
            {
                // Left unimplemented
                return false;
            }

            public boolean onDoubleTap(MotionEvent e)
            {
                if (settings.isFlipEnabled() && droneControlService != null) {
                    droneControlService.doLeftFlip();
                    return true;
                }

                return false;
            }
        });
    }

    
//    private void initVirtualJoysticks(JoystickType leftType, JoystickType rightType, boolean isLeftHanded)
//    {
//        JoystickBase joystickLeft = (!isLeftHanded ? view.getJoystickLeft() : view.getJoystickRight());
//        JoystickBase joystickRight = (!isLeftHanded ? view.getJoystickRight() : view.getJoystickLeft());
//
//        ApplicationSettings settings = getSettings();
//
//        if (leftType == JoystickType.ANALOGUE) {
//            if (joystickLeft == null || !(joystickLeft instanceof AnalogueJoystick) || joystickLeft.isAbsoluteControl() != settings.isAbsoluteControlEnabled()) {
//                joystickLeft = JoystickFactory.createAnalogueJoystick(this, settings.isAbsoluteControlEnabled(), rollPitchListener);
//            } else {
//                joystickLeft.setOnAnalogueChangedListener(rollPitchListener);
//                joystickRight.setAbsolute(settings.isAbsoluteControlEnabled());
//            }
//        } else if (leftType == JoystickType.ACCELERO) {
//            if (joystickLeft == null || !(joystickLeft instanceof AcceleroJoystick) || joystickLeft.isAbsoluteControl() != settings.isAbsoluteControlEnabled()) {
//                joystickLeft = JoystickFactory.createAcceleroJoystick(this, settings.isAbsoluteControlEnabled(), rollPitchListener);
//            } else {
//                joystickLeft.setOnAnalogueChangedListener(rollPitchListener);
//                joystickRight.setAbsolute(settings.isAbsoluteControlEnabled());
//            }
//        }
//
//        if (rightType == JoystickType.ANALOGUE) {
//            if (joystickRight == null || !(joystickRight instanceof AnalogueJoystick) || joystickRight.isAbsoluteControl() != settings.isAbsoluteControlEnabled()) {
//                joystickRight = JoystickFactory.createAnalogueJoystick(this, false, gazYawListener);
//            } else {
//                joystickRight.setOnAnalogueChangedListener(gazYawListener);
//                joystickRight.setAbsolute(false);
//            }
//        } else if (rightType == JoystickType.ACCELERO) {
//            if (joystickRight == null || !(joystickRight instanceof AcceleroJoystick) || joystickRight.isAbsoluteControl() != settings.isAbsoluteControlEnabled()) {
//                joystickRight = JoystickFactory.createAcceleroJoystick(this, false, gazYawListener);
//            } else {
//                joystickRight.setOnAnalogueChangedListener(gazYawListener);
//                joystickRight.setAbsolute(false);
//            }
//        }
//
////        if (!isLeftHanded) {
////            view.setJoysticks(joystickLeft, joystickRight);
////        } else {
////            view.setJoysticks(joystickRight, joystickLeft);
////        }
//    }

    @Override
    protected void onDestroy()
    {
        if (view != null) {
            view.onDestroy();
        }

        this.deviceOrientationManager.destroy();

        soundPool.release();
        soundPool = null;

        unbindService(mConnection);

        super.onDestroy();
        Log.d(TAG, "ControlDroneActivity destroyed");
        System.gc();
    }

    private void registerReceivers()
    {
        // System wide receiver
        registerReceiver(wifiSignalReceiver, new IntentFilter(WifiManager.RSSI_CHANGED_ACTION));

        // Local receivers
        LocalBroadcastManager localBroadcastMgr = LocalBroadcastManager.getInstance(getApplicationContext());
        localBroadcastMgr.registerReceiver(videoRecordingStateReceiver, new IntentFilter(DroneControlService.VIDEO_RECORDING_STATE_CHANGED_ACTION));
        localBroadcastMgr.registerReceiver(droneEmergencyReceiver, new IntentFilter(DroneControlService.DRONE_EMERGENCY_STATE_CHANGED_ACTION));
        localBroadcastMgr.registerReceiver(droneBatteryReceiver, new IntentFilter(DroneControlService.DRONE_BATTERY_CHANGED_ACTION));
        localBroadcastMgr.registerReceiver(droneFlyingStateReceiver, new IntentFilter(DroneControlService.DRONE_FLYING_STATE_CHANGED_ACTION));
        localBroadcastMgr.registerReceiver(droneCameraReadyChangedReceiver, new IntentFilter(DroneControlService.CAMERA_READY_CHANGED_ACTION));
        localBroadcastMgr.registerReceiver(droneRecordReadyChangeReceiver, new IntentFilter(DroneControlService.RECORD_READY_CHANGED_ACTION));
    }

    private void unregisterReceivers()
    {
        // Unregistering system receiver
        unregisterReceiver(wifiSignalReceiver);

        // Unregistering local receivers
        LocalBroadcastManager localBroadcastMgr = LocalBroadcastManager.getInstance(getApplicationContext());
        localBroadcastMgr.unregisterReceiver(videoRecordingStateReceiver);
        localBroadcastMgr.unregisterReceiver(droneEmergencyReceiver);
        localBroadcastMgr.unregisterReceiver(droneBatteryReceiver);
        localBroadcastMgr.unregisterReceiver(droneFlyingStateReceiver);
        localBroadcastMgr.unregisterReceiver(droneCameraReadyChangedReceiver);
        localBroadcastMgr.unregisterReceiver(droneRecordReadyChangeReceiver);
    }

    @Override
    protected void onResume()
    {
        if (view != null) {
            view.onResume();
        }

        if (droneControlService != null) {
            droneControlService.resume();
        }

        registerReceivers();
        refreshWifiSignalStrength();

        // Start tracking device orientation
        deviceOrientationManager.resume();
        magnetoAvailable = deviceOrientationManager.isMagnetoAvailable();

        super.onResume();
    }

    @Override
    protected void onPause()
    {
        if (view != null) {
            view.onPause();
        }

        if (droneControlService != null) {
            droneControlService.pause();
        }

        unregisterReceivers();

        // Stop tracking device orientation
        deviceOrientationManager.pause();

        stopEmergencySound();

        System.gc();
        super.onPause();
    }

    /**
     * Called when we connected to DroneControlService
     */
    protected void onDroneServiceConnected()
    {
        if (droneControlService != null) {
            droneControlService.resume();
            droneControlService.requestDroneStatus();
        } else {
            Log.w(TAG, "DroneServiceConnected event ignored as DroneControlService is null");
        }

        settingsDialog = new SettingsDialog(this, this, droneControlService, magnetoAvailable);

        applySettings(settings);

        initListeners();
        runTranscoding();
        
        if (droneControlService.getMediaDir() != null) {
            view.setRecordButtonEnabled(true);
//            view.setCameraButtonEnabled(true);
        }
    }


    @Override
    public void onDroneFlyingStateChanged(boolean flying)
    {
        this.flying = flying;
        view.setIsFlying(flying);

        updateBackButtonState();
    }

    @SuppressLint("NewApi")
    public void onDroneRecordReadyChanged(boolean ready)
    {
        if (!recording) {
            view.setRecordButtonEnabled(ready);
        } else {
            view.setRecordButtonEnabled(true);
        }
    }


    protected void onNotifyLowDiskSpace()
    {
        showWarningDialog(getString(R.string.your_device_is_low_on_disk_space), WARNING_MESSAGE_DISMISS_TIME);
    }


    protected void onNotifyLowUsbSpace()
    {
        showWarningDialog(getString(R.string.USB_drive_full_Please_connect_a_new_one), WARNING_MESSAGE_DISMISS_TIME);
    }


    protected void onNotifyNoMediaStorageAvailable()
    {
        showWarningDialog(getString(R.string.Please_insert_a_SD_card_in_your_Smartphone), WARNING_MESSAGE_DISMISS_TIME);
    }


    public void onCameraReadyChanged(boolean ready)
    {
//        view.setCameraButtonEnabled(ready);
        cameraReady = ready;
        
        updateBackButtonState();
    }
    

    public void onDroneEmergencyChanged(int code)
    {
        view.setEmergency(code);

        if (code == NavData.ERROR_STATE_EMERGENCY_VBAT_LOW || code == NavData.ERROR_STATE_ALERT_VBAT_LOW) {
            playEmergencySound();
        } else {
            stopEmergencySound();
        }

        controlLinkAvailable = (code != NavData.ERROR_STATE_NAVDATA_CONNECTION); 
        
        if (!controlLinkAvailable) {
            view.setRecordButtonEnabled(false);
//            view.setCameraButtonEnabled(false);
//            view.setSwitchCameraButtonEnabled(false);
        } else {
            view.setSwitchCameraButtonEnabled(true);
            view.setRecordButtonEnabled(true);
//            view.setCameraButtonEnabled(true);
        }
        
        updateBackButtonState();
        
        view.setEmergencyButtonEnabled(!NavData.isEmergency(code));
    }
    
    public void onDroneBatteryChanged(int value)
    {
        view.setBatteryValue(value);
    }

    public void onWifiSignalStrengthChanged(int strength)
    {
        view.setWifiValue(strength);
    }


    public void onDroneRecordVideoStateChanged(boolean recording, boolean usbActive, int remaining)
    {
        if (droneControlService == null)
            return;
        
        prevRecording = this.recording;
        this.recording = recording;

        view.setRecording(recording);
//        view.setUsbIndicatorEnabled(usbActive);
        view.setUsbRemainingTime(remaining);

        updateBackButtonState();

        if (!recording) {
            if (prevRecording != recording && droneControlService != null
                    && droneControlService.getDroneVersion() == EDroneVersion.DRONE_1) {
                runTranscoding();
                showWarningDialog(getString(R.string.Your_video_is_being_processed_Please_do_not_close_application), WARNING_MESSAGE_DISMISS_TIME);
            }
        }
        
        if (prevRecording != recording) {
            if (usbActive && droneControlService.getDroneConfig().isRecordOnUsb() && remaining == 0) {
                onNotifyLowUsbSpace();
            }
        }
    }

    protected void showSettingsDialog()
    {
        view.setSettingsButtonEnabled(false);
        
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag("settings");

        if (prev != null) {
            return;
        }

        ft.addToBackStack(null);

        settingsDialog.show(ft, "settings");
        
        if (pauseVideoWhenOnSettings) {
            view.onPause();
        }
    }

    @Override
    public void onBackPressed()
    {
        if (canGoBack()) {
            super.onBackPressed();
        }
    }
    
    
    private boolean canGoBack()
    {
        return !((flying || recording || !cameraReady) && controlLinkAvailable);
    }
    
    
    private void applyJoypadConfig(ControlMode controlMode, boolean isLeftHanded)
    {
//        switch (controlMode) {
//        case NORMAL_MODE:
//            initVirtualJoysticks(JoystickType.ANALOGUE, JoystickType.ANALOGUE, isLeftHanded);
//            acceleroEnabled = false;
//            break;
//        case ACCELERO_MODE:
//            initVirtualJoysticks(JoystickType.ACCELERO, JoystickType.ANALOGUE, isLeftHanded);
//            acceleroEnabled = true;
//            break;
//        case ACE_MODE:
//            initVirtualJoysticks(JoystickType.NONE, JoystickType.COMBINED, isLeftHanded);
//            acceleroEnabled = true;
//            break;
//        }
    }
    
    
    private void applySettings(ApplicationSettings settings)
    {
        applySettings(settings, false);
    }

    private void applySettings(ApplicationSettings settings, boolean skipJoypadConfig)
    {
        magnetoEnabled = settings.isAbsoluteControlEnabled();

        if (magnetoEnabled) {
            if (droneControlService.getDroneVersion() == EDroneVersion.DRONE_1 || !deviceOrientationManager.isMagnetoAvailable() || NookUtils.isNook()) {
                // Drone 1 doesn't have compass, so we need to switch magneto
                // off.
                magnetoEnabled = false;
                settings.setAbsoluteControlEnabled(false);
            }
        }

        if (droneControlService != null)
            droneControlService.setMagnetoEnabled(magnetoEnabled);

        if (!skipJoypadConfig) {
            applyJoypadConfig(settings.getControlMode(), settings.isLeftHanded());
        }

        // TODO we have to hide touch controllers for google TV
        view.setInterfaceOpacity(this.isGoogleTV ? 0 : settings.getInterfaceOpacity());
    }

    private ApplicationSettings getSettings()
    {
        return ((FreeFlightApplication) getApplication()).getAppSettings();
    }

    public void refreshWifiSignalStrength()
    {
        WifiManager manager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        int signalStrength = WifiManager.calculateSignalLevel(manager.getConnectionInfo().getRssi(), 4);
        onWifiSignalStrengthChanged(signalStrength);
    }

    private void showWarningDialog(final String message, final int forTime)
    {
        final String tag = message;
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag(tag);
        
        if (prev != null) {
            return;
        }

        ft.addToBackStack(null);

        // Create and show the dialog.
        WarningDialog dialog = new WarningDialog();

        dialog.setMessage(message);
        dialog.setDismissAfter(forTime);
        dialog.show(ft, tag);
    }

    private void playEmergencySound()
    {
        if (effectsStreamId != 0) {
            soundPool.stop(effectsStreamId);
            effectsStreamId = 0;
        }

        effectsStreamId = soundPool.play(batterySoundId, 1, 1, 1, -1, 1);
    }

    
    private void stopEmergencySound()
    {
        soundPool.stop(effectsStreamId);
        effectsStreamId = 0;
    }

    
    private void updateBackButtonState()
    {
        if (canGoBack()) {
            view.setBackButtonVisible(true);
        } else {
            view.setBackButtonVisible(false);   
        }
    }
    
    
    private void runTranscoding()
    {
        if (droneControlService.getDroneVersion() == EDroneVersion.DRONE_1) {
        	File mediaDir = droneControlService.getMediaDir();
        	
        	if (mediaDir != null) {
	            Intent transcodeIntent = new Intent(this, TranscodingService.class);
	            transcodeIntent.putExtra(TranscodingService.EXTRA_MEDIA_PATH, mediaDir.toString());
	            startService(transcodeIntent);
        	} else {
        		Log.d(TAG, "Transcoding skipped SD card is missing.");
        	}
        }
    }

    public void onDeviceOrientationChanged(float[] orientation, float magneticHeading, int magnetoAccuracy)
    {
        if (droneControlService == null) {
            return;
        }

        if (magnetoEnabled && magnetoAvailable) {
            float heading = magneticHeading * 57.2957795f;

            if (screenRotationIndex == 1) {
                heading += 90.f;
            }

            droneControlService.setDeviceOrientation((int) heading, 0);
        } else {
            droneControlService.setDeviceOrientation(0, 0);
        }

        if (running == false) {
            pitchBase = orientation[PITCH];
            rollBase = orientation[ROLL];
            droneControlService.setPitch(0);
            droneControlService.setRoll(0);
        } else {

            float x = (orientation[PITCH] - pitchBase);
            float y = (orientation[ROLL] - rollBase);

            if (screenRotationIndex == 0) {
                // Xoom
                if (acceleroEnabled && (Math.abs(x) > ACCELERO_TRESHOLD || Math.abs(y) > ACCELERO_TRESHOLD)) {
                    x *= -1;
                    droneControlService.setPitch(x);
                    droneControlService.setRoll(y);
                }
            } else if (screenRotationIndex == 1) {
                if (acceleroEnabled && (Math.abs(x) > ACCELERO_TRESHOLD || Math.abs(y) > ACCELERO_TRESHOLD)) {
                    x *= -1;
                    y *= -1;

                    droneControlService.setPitch(y);
                    droneControlService.setRoll(x);
                }
            } else if (screenRotationIndex == 3) {
                // google tv
                if (acceleroEnabled && (Math.abs(x) > ACCELERO_TRESHOLD || Math.abs(y) > ACCELERO_TRESHOLD)) {

                    droneControlService.setPitch(y);
                    droneControlService.setRoll(x);
                }
            }
        }
    }

    public void prepareDialog(SettingsDialog dialog)
    {
        dialog.setAcceleroAvailable(deviceOrientationManager.isAcceleroAvailable());

        if (NookUtils.isNook()) {
            // System see the magnetometer but actually it is not functional. So
            // we need to disable magneto
            dialog.setMagnetoAvailable(false);
        } else {
            dialog.setMagnetoAvailable(deviceOrientationManager.isMagnetoAvailable());
        }

        dialog.setFlying(flying);
        dialog.setConnected(controlLinkAvailable);
        dialog.enableAvailableSettings();
    }

    
    @Override
    public void onOptionChangedApp(SettingsDialog dialog, EAppSettingProperty property, Object value) 
    {
        if (value == null || value == null) {
            throw new IllegalArgumentException("Property can not be null");
        }
        
        ApplicationSettings appSettings = getSettings();
        
        switch (property) {
        case LEFT_HANDED_PROP:
        case MAGNETO_ENABLED_PROP:
        case CONTROL_MODE_PROP:
            applyJoypadConfig(appSettings.getControlMode(), appSettings.isLeftHanded());
            break;
        case INTERFACE_OPACITY_PROP:
            if (value instanceof Integer) {
                if (!isGoogleTV) {
                    view.setInterfaceOpacity(((Integer) value).floatValue());
                }
            }
            break;
        default:
            // Ignoring any other option change. They should be processed in onDismissed
            
        }
    }
    
    
    @Override
    public void onDismissed(SettingsDialog settingsDialog)
    {
        if (this.isGoogleTV) {
            this.applyHandDependendTVControllers();
        }
         	 
    	// pauseVideoWhenOnSettings option is not mandatory and is set depending to device in config.xml.
        if (pauseVideoWhenOnSettings) {
            view.onResume();
        }

        AsyncTask<Integer, Integer, Boolean> loadPropTask = new AsyncTask<Integer, Integer, Boolean>()
        {
            @Override
            protected Boolean doInBackground(Integer... params)
            {
                // Skipping joypad configuration as it was already done in onPropertyChanged
                // We do this because on some devices joysticks re-initialization takes too
                // much time.
                applySettings(getSettings(), true);
                return Boolean.TRUE;
            }

            @Override
            protected void onPostExecute(Boolean result)
            {
                view.setSettingsButtonEnabled(true);
            }
        };

        loadPropTask.execute();
    }
    

    private boolean isLowOnDiskSpace()
    {
        boolean lowOnSpace = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            DroneConfig config = droneControlService.getDroneConfig();
            if (!recording && !config.isRecordOnUsb()) {
                File mediaDir = droneControlService.getMediaDir();
                long freeSpace = 0;
                
                if (mediaDir != null) {
                    freeSpace = mediaDir.getUsableSpace();
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD
                        && freeSpace < LOW_DISK_SPACE_BYTES_LEFT) {
                    lowOnSpace = true;
                }
            }
        } else {
            // TODO: Provide alternative implementation. Probably using StatFs
        }
        
        return lowOnSpace;
    }

    private void onRecord()
    {
        if (droneControlService != null) {
            DroneConfig droneConfig = droneControlService.getDroneConfig();
            
            boolean sdCardMounted = droneControlService.isMediaStorageAvailable();
            boolean recordingToUsb = droneConfig.isRecordOnUsb() && droneControlService.isUSBInserted();

            if (recording) {
                // Allow to stop recording
                view.setRecordButtonEnabled(false);
                droneControlService.record();
            } else {           
                // Start recording
                if (!sdCardMounted) {
                    if (recordingToUsb) {
                        view.setRecordButtonEnabled(false);
                        droneControlService.record();
                    } else {
                        onNotifyNoMediaStorageAvailable();
                    }
                } else {
                    if (!recordingToUsb && isLowOnDiskSpace()) {
                        onNotifyLowDiskSpace();
                    }
                    
                    view.setRecordButtonEnabled(false);
                    droneControlService.record();
                }      
            }
        }
    }

    protected void onTakePhoto()
    {
        if (droneControlService.isMediaStorageAvailable()) {
            view.setCameraButtonEnabled(false);
            droneControlService.takePhoto();
        } else {
           onNotifyNoMediaStorageAvailable();
        }
    }
}
