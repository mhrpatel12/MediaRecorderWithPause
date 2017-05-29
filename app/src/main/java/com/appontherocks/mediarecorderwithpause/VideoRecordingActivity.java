package com.appontherocks.mediarecorderwithpause;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.appontherocks.mediarecorderwithpause.media.CameraHelper;
import com.coremedia.iso.boxes.Container;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.AppendTrack;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class VideoRecordingActivity extends AppCompatActivity implements MediaRecorder.OnInfoListener, SensorEventListener, TextureView.SurfaceTextureListener {

    @BindView(R.id.button_capture)
    ImageButton btnCapture;
    @BindView(R.id.button_pause)
    ImageButton btnPause;

    @BindView(R.id.surface_view)
    TextureView mPreview;

    @BindView(R.id.layout_pause_frame)
    FrameLayout layoutPauseFrame;

    private Camera mCamera;

    private MediaRecorder mMediaRecorder;
    private File mOutputFile;
    private File mOutputFileCompressed;

    private boolean isRecording = false;
    private static final String TAG = "Recorder";

    private SensorManager mSensorManager;
    private Sensor mOrientation;

    private int currentDisplayRotation;

    private Handler handler;

    private List<String> listVideoUris;

    private boolean isTimerPaused = false;
    private long remainingTime = 45000;
    private static final int COMPRESSED_VIDEO_ACTIVITY_REQUEST_CODE​ = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_recording);
        ButterKnife.bind(this);

        listVideoUris = new ArrayList<>();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mOrientation = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);

        try {
            mPreview.setSurfaceTextureListener(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @OnClick(R.id.button_capture)
    public void startStopRecording(View view) {

    }

    @OnClick(R.id.button_pause)
    public void pauseResumeRecording(View view) {
        if (isRecording) {
            try {
                mMediaRecorder.stop();  // stop the recording
            } catch (RuntimeException e) {
                // RuntimeException is thrown when stop() is called immediately after start().
                // In this case the output file is not properly constructed ans should be deleted.
                Log.d(TAG, "RuntimeException: stop() is called immediately after start()");
                //noinspection ResultOfMethodCallIgnored
                mOutputFile.delete();
            }
            releaseMediaRecorder(); // release the MediaRecorder object
            mCamera.lock();         // take camera access back from MediaRecorder

            // inform the user that recording has stopped
            isRecording = false;
            listVideoUris.add(mOutputFile + "");
            btnPause.setBackgroundResource(R.drawable.ic_play_arrow_white_24dp);
            btnPause.setTag(getString(R.string.tag_resume));
            // END_INCLUDE(stop_release_media_recorder)
            // stop recording and release camera
            isTimerPaused = false;
        } else {
            releaseCamera();
            // BEGIN_INCLUDE(prepare_start_media_recorder)
            VideoRecordingActivity.this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

            new MediaPrepareTask().execute(null, null, null);

            isTimerPaused = true;
            btnPause.setBackgroundResource(R.drawable.ic_pause_black_24dp);
            btnPause.setTag(getString(R.string.tag_pause));
            // END_INCLUDE(prepare_start_media_recorder)
        }
    }

    class MediaPrepareTask extends AsyncTask<Void, Void, Boolean> {

        @Override
        protected Boolean doInBackground(Void... voids) {
            // initialize video camera
            if (prepareVideoRecorder()) {
                // Camera is available and unlocked, MediaRecorder is prepared,
                // now you can start recording
                mMediaRecorder.start();

                isRecording = true;
                if (!isTimerPaused) {
                    isTimerPaused = true;
                }
            } else {
                // prepare didn't work, release the camera
                releaseMediaRecorder();
                return false;
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (!result) {
            }
            // inform the user that recording has started
        }
    }

    private void releaseMediaRecorder() {
        if (mMediaRecorder != null) {
            // clear recorder configuration
            mMediaRecorder.reset();
            // release the recorder object
            mMediaRecorder.release();
            mMediaRecorder = null;
            // Lock camera for later use i.e taking it back from MediaRecorder.
            // MediaRecorder doesn't need it anymore and we will release it if the activity pauses.
            mCamera.lock();
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            // release the camera for other applications
            mCamera.release();
            mCamera = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private boolean prepareVideoRecorder() {
        // BEGIN_INCLUDE (configure_preview)
        mCamera = CameraHelper.getDefaultCameraInstance(VideoRecordingActivity.this);

        // We need to make sure that our preview and recording video size are supported by the
        // camera. Query camera to find all the sizes and choose the optimal size given the
        // dimensions of our preview surface.
        Camera.Parameters parameters = mCamera.getParameters();
        List<Camera.Size> mSupportedPreviewSizes = parameters.getSupportedPreviewSizes();
        List<Camera.Size> mSupportedVideoSizes = parameters.getSupportedVideoSizes();
        Camera.Size optimalSize = CameraHelper.getOptimalVideoSize(mSupportedVideoSizes,
                mSupportedPreviewSizes, mPreview.getWidth(), mPreview.getHeight());

        // Use the same size for recording profile.
        CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_QVGA);
        profile.videoFrameWidth = optimalSize.width;
        profile.videoFrameHeight = optimalSize.height;

        // likewise for the camera object itself.
        parameters.setPreviewSize(profile.videoFrameWidth, profile.videoFrameHeight);
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        mCamera.setParameters(parameters);
        try {
            // Requires API level 11+, For backward compatibility use {@link setPreviewDisplay}
            // with {@link SurfaceView}
            mCamera.setPreviewTexture(mPreview.getSurfaceTexture());
        } catch (IOException e) {
            Log.e(TAG, "Surface texture is unavailable or unsuitable" + e.getMessage());
            return false;
        }
        // END_INCLUDE (configure_preview)


        // BEGIN_INCLUDE (configure_media_recorder)
        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setOnInfoListener(this);

        // Step 1: Unlock and set camera to MediaRecorder
        mCamera.unlock();
        mMediaRecorder.setCamera(mCamera);

        // Step 2: Set sources
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        // Step 3: Set a CamcorderProfile (requires API Level 8 or higher)
        mMediaRecorder.setProfile(profile);

        // Step 4: Set output file
        mOutputFile = CameraHelper.getOutputMediaFile(CameraHelper.MEDIA_TYPE_VIDEO);
        mOutputFileCompressed = CameraHelper.getOutputMediaFileCompressed(CameraHelper.MEDIA_TYPE_VIDEO);
        if ((mOutputFile == null) || (mOutputFileCompressed == null)) {
            return false;
        }
/*        if ((mOutputFile == null)) {
            return false;
        }*/
        mMediaRecorder.setOutputFile(mOutputFile.getPath());
        // END_INCLUDE (configure_media_recorder)

        // Step 5: Prepare configured MediaRecorder
        try {
            mMediaRecorder.setMaxDuration((int) remainingTime);
            mMediaRecorder.prepare();
        } catch (IllegalStateException e) {
            Log.d(TAG, "IllegalStateException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        } catch (IOException e) {
            Log.d(TAG, "IOException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        }
        return true;
    }

    public void onAccuracyChanged(Sensor arg0, int arg1) {
        // TODO Auto-generated method stub

    }

    public void onSensorChanged(SensorEvent event) {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int cameraId = -1;
        int numberOfCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                cameraId = i;
                break;
            }
        }
        if (cameraId != -1 && currentDisplayRotation != rotation) {
            if (!isRecording)
                setCameraOrientation(cameraId, mCamera);
        }
    }

    private void setCameraOrientation(int camId, Camera camera) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(camId, info);

        currentDisplayRotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;

        switch (currentDisplayRotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int result;
        result = (info.orientation + degrees) % 360;
        result = (360 - result) % 360;  //compensate for mirror effect

        if (Build.VERSION.SDK_INT < 14)
            camera.stopPreview();
        camera.setDisplayOrientation(result);
        if (Build.VERSION.SDK_INT < 14)
            camera.startPreview();
    }

    @Override
    public void onInfo(MediaRecorder mediaRecorder, int i, int i1) {


        Log.e(i1 + "", "");
        if (i == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {

            btnCapture.setBackgroundResource(R.drawable.ic_record_white_24dp);
            btnPause.setBackgroundResource(0);
            layoutPauseFrame.setVisibility(View.GONE);

            // stop recording and release camera
            try {
                mMediaRecorder.stop();  // stop the recording
            } catch (RuntimeException e) {
                // RuntimeException is thrown when stop() is called immediately after start().
                // In this case the output file is not properly constructed ans should be deleted.
                Log.d(TAG, "RuntimeException: stop() is called immediately after start()");
                //noinspection ResultOfMethodCallIgnored
                mOutputFile.delete();
            }
            releaseMediaRecorder(); // release the MediaRecorder object
            mCamera.lock();         // take camera access back from MediaRecorder

            // inform the user that recording has stopped
            isRecording = false;
            releaseCamera();

            if (listVideoUris.size() >= 2) {
                try {
                    listVideoUris.add(mOutputFile + "");
                    //File folder = new File(Environment.getExternalStorageDirectory() + File.separator + "Download/");
                    File folder = new File(Environment
                            .getExternalStoragePublicDirectory(Environment.getExternalStorageDirectory().getAbsolutePath()),
                            "SelfInspectionAppVideos");
                    // Create a media file name
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    String filename = folder.getPath() + File.separator + "Inspection_Video_" + timeStamp + ".mp4";


                    Movie[] inMovies = new Movie[listVideoUris.size()];

                    for (int j = 0; j < listVideoUris.size(); j++) {
                        File file = new File(listVideoUris.get(j));
                        inMovies[j] = MovieCreator.build(file.getAbsolutePath());
                    }

                    LinkedList<Track> videoTracks = new LinkedList<Track>();
                    LinkedList<Track> audioTracks = new LinkedList<Track>();

/*                            CroppedTrack aacTrackShort = new CroppedTrack(aacTrack, 1, aacTrack.getSamples().size());
                            audioTracks.add(aacTrackShort);
                            videoTracks.add(inMovies[0].getTracks().get(0));*/

                    for (Movie m : inMovies) {
                        for (Track t : m.getTracks()) {
                            if (t.getHandler().equals("soun")) {
                                audioTracks.add(t);
                            }
                            if (t.getHandler().equals("vide")) {
                                videoTracks.add(t);
                            }
                        }
                    }

                    //Result movie from putting the audio and video together from the two clips
                    Movie result = new Movie();

                    //Append all audio and video
                    if (videoTracks.size() > 0)
                        result.addTrack(new AppendTrack(videoTracks.toArray(new Track[videoTracks.size()])));

                    if (audioTracks.size() > 0)
                        result.addTrack(new AppendTrack(audioTracks.toArray(new Track[audioTracks.size()])));
                    //result.addTrack(aacTrackShort);


                    Container out = new DefaultMp4Builder().build(result);
                    FileChannel fc = new RandomAccessFile(String.format(filename), "rw").getChannel();

                    out.writeContainer(fc);
                    fc.close();

                    mOutputFile = new File(filename);

                    finishRecording();

                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (NullPointerException e) {
                    e.printStackTrace();
                } catch (OutOfMemoryError e) {
                    e.printStackTrace();
                }
            } else if (listVideoUris.size() == 1) {
                try {
                    listVideoUris.add(mOutputFile + "");
                    //File folder = new File(Environment.getExternalStorageDirectory() + File.separator + "Download/");
                    File folder = new File(Environment
                            .getExternalStoragePublicDirectory(Environment.getExternalStorageDirectory().getAbsolutePath()),
                            "SelfInspectionAppVideos");
                    // Create a media file name
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    String filename = folder.getPath() + File.separator + "Inspection_Video_" + timeStamp + ".mp4";


                    Movie[] inMovies = new Movie[listVideoUris.size()];

                    for (int j = 0; j < listVideoUris.size(); j++) {
                        File file = new File(listVideoUris.get(j));
                        inMovies[j] = MovieCreator.build(file.getAbsolutePath());
                    }

                    LinkedList<Track> videoTracks = new LinkedList<Track>();
                    LinkedList<Track> audioTracks = new LinkedList<Track>();

/*                            CroppedTrack aacTrackShort = new CroppedTrack(aacTrack, 1, aacTrack.getSamples().size());
                            audioTracks.add(aacTrackShort);
                            videoTracks.add(inMovies[0].getTracks().get(0));*/

                    for (Movie m : inMovies) {
                        for (Track t : m.getTracks()) {
                            if (t.getHandler().equals("soun")) {
                                audioTracks.add(t);
                            }
                            if (t.getHandler().equals("vide")) {
                                videoTracks.add(t);
                            }
                        }
                    }

                    //Result movie from putting the audio and video together from the two clips
                    Movie result = new Movie();

                    //Append all audio and video
                    if (videoTracks.size() > 0)
                        result.addTrack(new AppendTrack(videoTracks.toArray(new Track[videoTracks.size()])));

                    if (audioTracks.size() > 0)
                        result.addTrack(new AppendTrack(audioTracks.toArray(new Track[audioTracks.size()])));
                    //result.addTrack(aacTrackShort);


                    Container out = new DefaultMp4Builder().build(result);
                    FileChannel fc = new RandomAccessFile(String.format(filename), "rw").getChannel();

                    out.writeContainer(fc);
                    fc.close();

                    mOutputFile = new File(filename);
                    //mOutputFile is a FINAL Video
                    finishRecording();

                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (NullPointerException e) {
                    e.printStackTrace();
                } catch (OutOfMemoryError e) {
                    e.printStackTrace();
                }
            } else {

                //mOutputFile;

                finishRecording();
            }
        }
    }

    public void finishRecording() {
        //finish();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mCamera = Camera.open();

        try {
            mCamera.setPreviewTexture(surface);
            mCamera.startPreview();
        } catch (IOException ioe) {
            // Something bad happened
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        try {
            mCamera.stopPreview();
            mCamera.release();
        } catch (Exception e) {
        }
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Invoked every time there's a new Camera preview frame
    }
}
