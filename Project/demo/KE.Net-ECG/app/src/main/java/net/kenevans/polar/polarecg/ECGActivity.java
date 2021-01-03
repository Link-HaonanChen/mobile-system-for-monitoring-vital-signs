package net.kenevans.polar.polarecg;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.res.ResourcesCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.androidplot.Plot;
import com.androidplot.PlotListener;
import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.PanZoom;
import com.androidplot.xy.StepMode;
import com.androidplot.xy.XYGraphWidget;
import com.androidplot.xy.XYPlot;

import org.reactivestreams.Publisher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.UUID;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import polar.com.sdk.api.PolarBleApi;
import polar.com.sdk.api.PolarBleApiCallback;
import polar.com.sdk.api.PolarBleApiDefaultImpl;
import polar.com.sdk.api.errors.PolarInvalidArgument;
import polar.com.sdk.api.model.PolarDeviceInfo;
import polar.com.sdk.api.model.PolarEcgData;
import polar.com.sdk.api.model.PolarHrData;
import polar.com.sdk.api.model.PolarSensorSetting;

public class ECGActivity extends AppCompatActivity implements PlotterListener {
    private String TAG = "Polar_ECG";
    private String sharedPrefsKey = "polar_ecg_id";
    SharedPreferences sharedPreferences;
    // The total number of points = 26 * total large blocks desired
    private static final int N_TOTAL_POINTS = 3900;  // 150 = 30 sec
    private static final int N_PLOT_POINTS = 520;    // 20 points
    private XYPlot mPlot;
    private Plotter mPlotter;
    private PlotListener mPlotListener;
    private boolean mOrientationChanged = false;

    private enum SAVE_TYPE {DATA, PLOT, BOTH}

    private boolean mPromptForWriteExternalStorage = true;
    private boolean mAllowWrite = false;

    /**
     * Request code for WRITE_EXTERNAL_STORAGE.
     */
    private static final int ACCESS_LOCATION_REQ = 1;
    private static final int ACCESS_WRITE_EXTERNAL_STORAGE_REQ = 2;
    TextView mTextViewHR, mTextViewFW, mTextViewTime;
    private PolarBleApi mApi;
    private Disposable mEcgDisposable;
    private boolean mPlaying;
    private Menu mMenu;
    private String mDeviceId = "";
    private String mFirmware;
    private String mBatteryLevel;

    // Used in Logging
    private long ecgTime0;
    private long redrawTime0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Log.d(TAG, this.getClass().getSimpleName() + " onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ecg);
        mTextViewHR = findViewById(R.id.info);
        mTextViewFW = findViewById(R.id.fw);
        mTextViewTime = findViewById(R.id.time);
        mPlot = findViewById(R.id.plot);

        sharedPreferences = this.getPreferences(Context.MODE_PRIVATE);

        // Start Bluetooth
        checkBT();
        mDeviceId = sharedPreferences.getString(sharedPrefsKey, "");
        Log.d(TAG, "    mDeviceId=" + mDeviceId);

        if (mDeviceId == null || mDeviceId.equals("")) {
            showDeviceIdDialog(null);
        }
    }

    @Override
    protected void onPause() {
        Log.v(TAG, this.getClass().getSimpleName() + " onPause");
        super.onPause();

        if(mApi != null) mApi.backgroundEntered();
    }

    @Override
    public void onResume() {
        Log.v(TAG, this.getClass().getSimpleName() + " onResume:"
                + " mOrientationChanged=" + mOrientationChanged);
        super.onResume();

        if(mApi != null) mApi.foregroundEntered();

        // Handle prompting for permissions
        mAllowWrite = ContextCompat.checkSelfPermission(this, Manifest
                .permission.WRITE_EXTERNAL_STORAGE) == PackageManager
                .PERMISSION_GRANTED;
        Log.d(TAG, "    mPlaying(start)=" + mPlaying);
        Log.d(TAG, "    mAllowWrite=" + mAllowWrite
                + " mPromptForWriteExternalStorage="
                + mPromptForWriteExternalStorage);
        Log.d(TAG, "    Build.VERSION.SDK_INT=" + Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 23
                && ContextCompat.checkSelfPermission(this, Manifest
                .permission.WRITE_EXTERNAL_STORAGE) != PackageManager
                .PERMISSION_GRANTED) {
            if (mPromptForWriteExternalStorage) {
                requestWriteExternalStoragePermission();
            }
        }

        invalidateOptionsMenu();

        // Setup the plot if not done
        Log.d(TAG, "    mPlotter=" + mPlotter);
        if (mPlotter == null) {
            mPlot.post(new Runnable() {
                @Override
                public void run() {
                    mPlotter = new Plotter(N_TOTAL_POINTS, N_PLOT_POINTS,
                            "ECG", Color.RED, false);
                    mPlotter.setListener(ECGActivity.this);
                    setupPlot();
                }
            });
        }

        // Start the connection to the device
        Log.d(TAG, "    mDeviceId=" + mDeviceId);
        Log.d(TAG, "    mApi=" + mApi);
        mDeviceId = sharedPreferences.getString(sharedPrefsKey, "");
        if (mDeviceId == null || mDeviceId.isEmpty()) {
            Toast.makeText(this,
                    getString(R.string.noDevice),
                    Toast.LENGTH_SHORT).show();
            return;
        } else {
            restart();
        }
        Log.d(TAG, "    onResume(end) mPlaying=" + mPlaying);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.v(TAG, this.getClass().getSimpleName() + " onCreateOptionsMenu");
        Log.d(TAG, "    mAllowWrite=" + mAllowWrite + " mPlaying=" + mPlaying);
        mMenu = menu;
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main_menu, menu);
        if (mApi == null) {
            mMenu.findItem(R.id.pause).setTitle("Start");
            mMenu.findItem(R.id.save_data).setVisible(false);
            mMenu.findItem(R.id.save_plot).setVisible(false);
            mMenu.findItem(R.id.save_both).setVisible(false);
        } else if (mPlaying) {
            mMenu.findItem(R.id.pause).setIcon(ResourcesCompat.
                    getDrawable(getResources(),
                            R.drawable.ic_stop_white_36dp, null));
            mMenu.findItem(R.id.pause).setTitle("Pause");
            mMenu.findItem(R.id.save_data).setVisible(false);
            mMenu.findItem(R.id.save_plot).setVisible(false);
            mMenu.findItem(R.id.save_both).setVisible(false);
        } else {
            mMenu.findItem(R.id.pause).setIcon(ResourcesCompat.
                    getDrawable(getResources(),
                            R.drawable.ic_play_arrow_white_36dp, null));
            mMenu.findItem(R.id.pause).setTitle("Start");
            mMenu.findItem(R.id.save_data).setVisible(mAllowWrite && true);
            mMenu.findItem(R.id.save_plot).setVisible(mAllowWrite && true);
            mMenu.findItem(R.id.save_both).setVisible(mAllowWrite && true);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.pause:
                if (mApi == null) {
                    return true;
                }
                if (mPlaying) {
                    // Turn it off
                    mPlaying = false;
                    allowPan(true);
                    if (mEcgDisposable != null) {
                        // Turns it off
                        streamECG();
                    }
                    mMenu.findItem(R.id.pause).setIcon(ResourcesCompat.
                            getDrawable(getResources(),
                                    R.drawable.ic_play_arrow_white_36dp, null));
                    mMenu.findItem(R.id.pause).setTitle("Start");
                    mMenu.findItem(R.id.save_data).setVisible(mAllowWrite && true);
                    mMenu.findItem(R.id.save_plot).setVisible(mAllowWrite && true);
                    mMenu.findItem(R.id.save_both).setVisible(mAllowWrite && true);
                } else {
                    // Turn it on
                    mPlaying = true;
                    allowPan(false);
                    mTextViewTime.setText(getString(R.string.elapsed_time,
                            0.0));
                    // Clear the plot
                    mPlotter.clear();
                    if (mEcgDisposable == null) {
                        // Turns it on
                        streamECG();
                    }
                    mMenu.findItem(R.id.pause).setIcon(ResourcesCompat.
                            getDrawable(getResources(),
                                    R.drawable.ic_stop_white_36dp, null));
                    mMenu.findItem(R.id.pause).setTitle("Pause");
                    mMenu.findItem(R.id.save_data).setVisible(false);
                    mMenu.findItem(R.id.save_plot).setVisible(false);
                    mMenu.findItem(R.id.save_both).setVisible(false);
                }
                return true;
            case R.id.save_plot:
                saveData(null, SAVE_TYPE.PLOT);
                return true;
            case R.id.save_data:
                saveData(null, SAVE_TYPE.DATA);
                return true;
            case R.id.save_both:
                saveData(null, SAVE_TYPE.BOTH);
                return true;
            case R.id.info:
                info();
                return true;
            case R.id.device_id:
                showDeviceIdDialog(null);
                return true;
        }
        return false;
    }

    public void showDeviceIdDialog(View view) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this,
                R.style.PolarTheme);
        dialog.setTitle(R.string.device_id_dialog_title);

        View viewInflated = LayoutInflater.from(getApplicationContext()).
                inflate(R.layout.device_id_dialog_layout,
                        view == null ? null : (ViewGroup) view.getRootView(),
                        false);

        final EditText input = viewInflated.findViewById(R.id.input);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        mDeviceId = sharedPreferences.getString(sharedPrefsKey, "");
        input.setText(mDeviceId);
        dialog.setView(viewInflated);

        dialog.setPositiveButton(R.string.ok,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String oldDeviceId = mDeviceId;
                        mDeviceId = input.getText().toString();
                        Log.d(TAG, "showDeviceIdDialog: OK:  oldDeviceId="
                                + oldDeviceId + " newDeviceId="
                                + mDeviceId);
                        SharedPreferences.Editor editor =
                                sharedPreferences.edit();
                        editor.putString(sharedPrefsKey, mDeviceId);
                        editor.apply();
                        if (!oldDeviceId.equals(mDeviceId)) {
                            if (mEcgDisposable != null) {
                                // Turns it off
                                streamECG();
                            }
                            if (mApi != null) {
                                try {
                                    mApi.disconnectFromDevice(mDeviceId);
                                } catch (PolarInvalidArgument ex) {
                                    String msg = "disconnectFromDevice: Bad " +
                                            "argument: mDeviceId"
                                            + mDeviceId;
                                    Utils.excMsg(ECGActivity.this, msg, ex);
                                    Log.d(TAG, this.getClass().getSimpleName()
                                            + " showDeviceIdDialog: " + msg);
                                }
                                mApi = null;
                            }
                            mPlaying = false;
                            mPlotter.clear();
                            mTextViewFW.setText("");
                            invalidateOptionsMenu();
                            restart();
                        }
                        if (mDeviceId == null || mDeviceId.isEmpty()) {
                            Toast.makeText(ECGActivity.this,
                                    getString(R.string.noDevice),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        dialog.setNegativeButton(R.string.cancel,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(TAG,
                                "showDeviceIdDialog: Cancel:  mDeviceId=" + mDeviceId);
                        dialog.cancel();
                        if (mDeviceId == null || mDeviceId.isEmpty()) {
                            Toast.makeText(ECGActivity.this,
                                    getString(R.string.noDevice),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        dialog.show();
    }


    /**
     * This is necessary to handle orientation changes and keep the plot. It
     * needs<br><br>
     * android:configChanges="orientation|keyboardHidden|screenSize"<br><br>
     * in AndroidManifest for the Activity.  With this set onPause and
     * onResume are not called, only this.  Otherwise orientation changes
     * cause it to start over with onCreate.  <br><br>
     * The screen orientation changes have not been made yet, so anything
     * relying on them must be done later.
     *
     * @param newConfig The new configuration.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.v(TAG, this.getClass().getSimpleName() +
                " onConfigurationChanged");
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.v(TAG, "    Landscape");
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Log.v(TAG, "    Portrait");
        }
        // Cannot do this now as the screen changes have only been dispatched
        mOrientationChanged = true;
        mPlot.post(new Runnable() {
            @Override
            public void run() {
                mPlot.redraw();
            }
        });
    }


    @Override
    public void onDestroy() {
        Log.v(TAG, this.getClass().getSimpleName() + " onDestroy");
        super.onDestroy();
        if (mApi != null) mApi.shutDown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[]
            permissions, @NonNull int[] grantResults) {
        Log.d(TAG, this.getClass().getSimpleName() + ": " +
                "onRequestPermissionsResult:" + " permissions=" +
                permissions[0]);
        Log.d(TAG, "    grantResults=" + grantResults[0]
                + " mPromptForReadExternalStorage="
                + mPromptForWriteExternalStorage);
        Log.d(TAG, "    requestCode=" + requestCode
                + " ACCESS_LOCATION_REQ="
                + ACCESS_LOCATION_REQ
                + " ACCESS_WRITE_EXTERNAL_STORAGE_REQ="
                + ACCESS_WRITE_EXTERNAL_STORAGE_REQ
        );
        switch (requestCode) {
            case ACCESS_WRITE_EXTERNAL_STORAGE_REQ:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "WRITE_EXTERNAL_STORAGE granted");
                    mAllowWrite = true;
                } else if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    Log.d(TAG, "WRITE_EXTERNAL_STORAGE denied");
                    mPromptForWriteExternalStorage = false;
                    mAllowWrite = false;
                }
                break;
        }
    }

    /**
     * Request permission for WRITE_EXTERNAL_STORAGE.
     */
    private void requestWriteExternalStoragePermission() {
        Log.d(TAG, this.getClass().getSimpleName() + " " +
                "requestWriteExternalStoragePermission");
        Log.d(TAG, "    Build.VERSION.SDK_INT=" + Build.VERSION.SDK_INT);
        Log.d(TAG, "    ACCESS_WRITE_EXTERNAL_STORAGE_REQ="
                + ACCESS_WRITE_EXTERNAL_STORAGE_REQ);
        ActivityCompat.requestPermissions(this, new String[]{Manifest
                        .permission.WRITE_EXTERNAL_STORAGE},
                ACCESS_WRITE_EXTERNAL_STORAGE_REQ);
    }

    /**
     * Sets the plot parameters, calculating the range boundaries to have the
     * same grid as the domain.  Calls update when done.
     */
    public void setupPlot() {
        Log.d(TAG, this.getClass().getSimpleName() + " setupPlot");
//        DisplayMetrics displayMetrics = this.getResources()
//        .getDisplayMetrics();
//        float dpHeight = displayMetrics.heightPixels / displayMetrics.density;
//        float dpWidth = displayMetrics.widthPixels / displayMetrics.density;
//        Log.d(TAG, "dpWidth=" + dpWidth + " dpHeight=" + dpHeight);
//        Log.d(TAG, "widthPixels=" + displayMetrics.widthPixels +
//                " heightPixels=" + displayMetrics.heightPixels);
//        Log.d(TAG, "density=" + displayMetrics.density);
//        Log.d(TAG, "10dp=" + 10 / displayMetrics.density + " pixels");
//
//        Log.d(TAG, "plotWidth=" + mPlot.getWidth() +
//                " plotHeight=" + mPlot.getHeight());
//
//        RectF widgetRect = mPlot.getGraph().getWidgetDimensions().canvasRect;
//        Log.d(TAG,
//                "widgetRect LRTB=" + widgetRect.left + "," + widgetRect
//                .right +
//                        "," + widgetRect.top + "," + widgetRect.bottom);
//        Log.d(TAG, "widgetRect width=" + (widgetRect.right - widgetRect
//        .left) +
//                " height=" + (widgetRect.bottom - widgetRect.top));
//
//        RectF gridRect = mPlot.getGraph().getGridRect();
//        Log.d(TAG, "gridRect LRTB=" + gridRect.left + "," + gridRect.right +
//                "," + gridRect.top + "," + gridRect.bottom);
//        Log.d(TAG, "gridRect width=" + (gridRect.right - gridRect.left) +
//                " height=" + (gridRect.bottom - gridRect.top));

        // Calculate the range limits to make the blocks be square
        // Using .5 mV and N_PLOT_POINTS / 130 Hz for total grid size
        // rMax is half the total, rMax at top and -rMax at bottom
        RectF gridRect = mPlot.getGraph().getGridRect();
        double rMax =
                .25 * (gridRect.bottom - gridRect.top) * N_PLOT_POINTS /
                        26 / (gridRect.right - gridRect.left);
        // Round it to one decimal point
        rMax = Math.round(rMax * 10) / 10.;
        Log.d(TAG, "    rMax = " + rMax);
        Log.d(TAG, "    gridRect LRTB=" + gridRect.left + "," + gridRect.right +
                "," + gridRect.top + "," + gridRect.bottom);
        Log.d(TAG, "    gridRect width=" + (gridRect.right - gridRect.left) +
                " height=" + (gridRect.bottom - gridRect.top));
        DisplayMetrics displayMetrics = this.getResources()
                .getDisplayMetrics();
        Log.d(TAG, "    display widthPixels=" + displayMetrics.widthPixels +
                " heightPixels=" + displayMetrics.heightPixels);

        mPlot.addSeries(mPlotter.getSeries(), mPlotter.getFormatter());
        mPlot.setRangeBoundaries(-rMax, rMax, BoundaryMode.FIXED);
        // Set the range block to be .1 mV so a large block will be .5 mV
        mPlot.setRangeStep(StepMode.INCREMENT_BY_VAL, .1);
        mPlot.setLinesPerRangeLabel(5);
        mPlot.setDomainBoundaries(0, N_PLOT_POINTS, BoundaryMode.FIXED);
        // Set the domain block to be .2 * 26 so large block will be 26 samples
        mPlot.setDomainStep(StepMode.INCREMENT_BY_VAL,
                .2 * 26);
        mPlot.setLinesPerDomainLabel(5);

        mPlot.getGraph().setLineLabelEdges(XYGraphWidget.Edge.NONE);

        // These don't work
//        mPlot.getTitle().position(0, HorizontalPositioning
//        .ABSOLUTE_FROM_RIGHT,
//                0,    VerticalPositioning.ABSOLUTE_FROM_TOP, Anchor
//                .RIGHT_TOP);
//        mPlot.getTitle().setAnchor(Anchor.BOTTOM_MIDDLE);
//        mPlot.getTitle().setMarginTop(200);
//        mPlot.getTitle().setPaddingTop(200);

//        mPlot.setRenderMode(Plot.RenderMode.USE_BACKGROUND_THREAD);

        update();
    }

    private void allowPan(boolean allow) {
        if (allow) {
            PanZoom.attach(mPlot, PanZoom.Pan.HORIZONTAL, PanZoom.Zoom.NONE);
        } else {
            PanZoom.attach(mPlot, PanZoom.Pan.NONE, PanZoom.Zoom.NONE);
        }
    }

    /**
     * Save the current samples as a file.  Prompts for a note, then calls
     * finishSaveData.
     */
    private void saveData(View view, final SAVE_TYPE saveType) {
        String msg;
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            msg = "External Storage is not available";
            Log.e(TAG, msg);
            Utils.errMsg(this, msg);
            return;
        }
        // Get a note
        AlertDialog.Builder dialog = new AlertDialog.Builder(this,
                R.style.PolarTheme);
        dialog.setTitle(R.string.note_dialog_title);

        View viewInflated = LayoutInflater.from(getApplicationContext()).
                inflate(R.layout.device_id_dialog_layout,
                        view == null ? null : (ViewGroup) view.getRootView(),
                        false);

        final EditText input = viewInflated.findViewById(R.id.input);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        dialog.setView(viewInflated);

        dialog.setPositiveButton(R.string.ok,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (saveType) {
                            case DATA:
                                finishSaveData(input.getText().toString());
                                break;
                            case PLOT:
                                finishSavePlot(input.getText().toString());
                                break;
                            case BOTH:
                                finishSaveData(input.getText().toString());
                                finishSavePlot(input.getText().toString());
                                break;
                        }
                    }
                });
        dialog.setNegativeButton(R.string.cancel,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                });
        dialog.show();
    }

    /**
     * Finishes the savePlot after getting the note.
     *
     * @param note The note.
     */
    private void finishSavePlot(String note) {
        String msg;
        File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        String format = "yyyy-MM-dd_HH-mm";
        SimpleDateFormat df = new SimpleDateFormat(format, Locale.US);
        Date now = new Date();
        LinkedList<Number> vals = mPlotter.getSeries().getyVals();
        int nSamples = vals.size();
        String fileName = "PolarECG-" + df.format(now) + ".png";
        File file = new File(dir, fileName);
        Bitmap bm = EcgImage.createImage(this,
                now.toString(),
                mDeviceId,
                mFirmware,
                mBatteryLevel,
                note,
                mTextViewHR.getText().toString(),
                String.format(Locale.US, "%.1f " +
                        "sec", nSamples / 130.),
                vals);
        try {
            FileOutputStream strm = new FileOutputStream(file);
            bm.compress(Bitmap.CompressFormat.PNG, 80, strm);
            strm.close();
            msg = "Wrote " + file.getPath();
            Log.d(TAG, msg);
            Utils.infoMsg(this, msg);
        } catch (IOException ex) {
            msg = "Error writing " + file.getPath();
            Log.e(TAG, msg);
            Log.e(TAG, Log.getStackTraceString(ex));
            Utils.excMsg(this, msg, ex);
        }
    }

    /**
     * Finishes the saveData after getting the note.
     *
     * @param note The note.
     */
    private void finishSaveData(String note) {
        String msg;
        File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        String format = "yyyy-MM-dd_HH-mm";
        SimpleDateFormat df = new SimpleDateFormat(format, Locale.US);
        Date now = new Date();
        String fileName = "PolarECG-" + df.format(now) + ".csv";
        File file = new File(dir, fileName);
        try (PrintWriter out = new PrintWriter(new FileWriter(file))) {
            // Write header
            out.write(now.toString() + "\n");
            // The text for this TextView already has a \n
            out.write(mTextViewFW.getText().toString());
            out.write(note + "\n");
            out.write("HR " + mTextViewHR.getText().toString() + "\n");
            // Write samples
            LinkedList<Number> vals = mPlotter.getSeries().getyVals();
            int nSamples = vals.size();
            out.write(nSamples + " values " + String.format(Locale.US, "%.1f " +
                    "sec\n", nSamples / 130.));
            for (Number val : vals) {
                out.write(String.format(Locale.US, "%.3f\n",
                        val.doubleValue()));
            }
            out.flush();
            msg = "Wrote " + file.getPath();
            Log.d(TAG, msg);
            Utils.infoMsg(this, msg);
        } catch (Exception ex) {
            msg = "Error writing " + file.getPath();
            Log.e(TAG, msg);
            Log.e(TAG, Log.getStackTraceString(ex));
            Utils.excMsg(this, msg, ex);
        }
        // Do nothing
    }

    public void info() {
        StringBuilder msg = new StringBuilder();
        msg.append("Device Id: ").append(mDeviceId).append("\n");
        msg.append("Firmware: ").append(mFirmware).append("\n");
        msg.append("Battery Level: ").append(mBatteryLevel).append("\n");
        msg.append("Connected: ").append((mApi != null)).append("\n");
        msg.append("Playing: ").append(mPlaying).append("\n");
        msg.append("Receiving ECG: ").append(mEcgDisposable != null).append(
                "\n");
        if (mPlotter != null && mPlotter.getSeries() !=
                null && mPlotter.getSeries().getyVals() != null) {
            double elapsed =
                    mPlotter.getDataIndex() / 130.;
            msg.append("Elapsed Time: ")
                    .append(getString(R.string.elapsed_time, elapsed)).append("\n");
            msg.append("Points plotted: ")
                    .append(mPlotter.getSeries().getyVals().size()).append(
                    "\n");
        }
        Single<PolarSensorSetting> ecgSettings = mApi.requestEcgSettings(mDeviceId);
        msg.append("Polar BLE API Version: ").
                append(PolarBleApiDefaultImpl.versionInfo()).append("\n");
        msg.append("Location Permission: ")
                .append((ContextCompat.checkSelfPermission(this, Manifest
                        .permission.ACCESS_FINE_LOCATION) == PackageManager
                        .PERMISSION_GRANTED)).append("\n");
        msg.append("Storage Permission: ")
                .append((ContextCompat.checkSelfPermission(this, Manifest
                        .permission.WRITE_EXTERNAL_STORAGE) == PackageManager
                        .PERMISSION_GRANTED)).append("\n");
        Utils.infoMsg(this, msg.toString());
    }

    /**
     * Toggles streaming for ECG.
     */
    public void streamECG() {
        Log.v(TAG, this.getClass().getSimpleName() + " streamECG:"
                + "mEcgDisposable=" + mEcgDisposable);
        if (mEcgDisposable == null) {
            mEcgDisposable =
                    mApi.requestEcgSettings(mDeviceId).
                            toFlowable().
                            flatMap(new Function<PolarSensorSetting,
                                    Publisher<PolarEcgData>>() {
                                @Override
                                public Publisher<PolarEcgData> apply(PolarSensorSetting sensorSetting) {
//                            Log.d(TAG, "mEcgDisposable requestEcgSettings " +
//                                    "apply");
//                            Log.d(TAG,
//                                    "sampleRate=" + sensorSetting
//                                    .maxSettings().settings.
//                                            get(PolarSensorSetting
//                                            .SettingType.SAMPLE_RATE) +
//                                            " resolution=" + sensorSetting
//                                            .maxSettings().settings.
//                                            get(PolarSensorSetting
//                                            .SettingType.RESOLUTION) +
//                                            " range=" + sensorSetting
//                                            .maxSettings().settings.
//                                            get(PolarSensorSetting
//                                            .SettingType.RANGE));
                                    return mApi.startEcgStreaming(mDeviceId,
                                            sensorSetting.maxSettings());
                                }
                            }).observeOn(AndroidSchedulers.mainThread()).subscribe(
                            new Consumer<PolarEcgData>() {
                                @Override
                                public void accept(PolarEcgData polarEcgData) {
//                                    double deltaT =
//                                            .000000001 * (polarEcgData
//                                            .timeStamp - ecgTime0);
//                                    ecgTime0 = polarEcgData.timeStamp;
//                                    int nSamples = polarEcgData.samples
//                                    .size();
//                                    double samplesPerSec = nSamples / deltaT;
//                                    Log.d(TAG,
//                                            "ecg update:" +
//                                                    " deltaT=" + String
//                                                    .format("%.3f", deltaT) +
//                                                    " nSamples=" + nSamples +
//                                                    " samplesPerSec=" +
//                                                    String.format("%.3f",
//                                                    samplesPerSec));

//                                    long now = new Date().getTime();
//                                    long ts =
//                                            polarEcgData.timeStamp / 1000000;
//                                    Log.d(TAG, "timeOffset=" + (now - ts) +
//                                            " " + new Date(now - ts));
                                    if (mPlaying) {
                                        mPlotter.addValues(mPlot, polarEcgData);
                                        double elapsed =
                                                mPlotter.getDataIndex() / 130.;
                                        mTextViewTime.setText(getString(R.string.elapsed_time, elapsed));
                                    }
                                }
                            },
                            new Consumer<Throwable>() {
                                @Override
                                public void accept(Throwable throwable) {
                                    Log.e(TAG,
                                            "" + throwable.getLocalizedMessage());
                                    mEcgDisposable = null;
                                }
                            },
                            new Action() {
                                @Override
                                public void run() {
                                    Log.d(TAG, "ECG streaming complete");
                                }
                            }
                    );
        } else {
            // NOTE stops streaming if it is "running"
            mEcgDisposable.dispose();
            mEcgDisposable = null;
        }
    }

    @Override
    public void update() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
//                Log.d(TAG, "update (UI) thread: " + Thread.currentThread()
//                .getName());
                mPlot.redraw();
            }
        });
    }

    public void checkBT() {
        BluetoothAdapter mBluetoothAdapter =
                BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter != null && !mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent =
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 2);
        }

        //requestPermissions() method needs to be called when the build SDK
        // version is 23 or above
        if (Build.VERSION.SDK_INT >= 23) {
            this.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION},
                    ACCESS_LOCATION_REQ);
        }
    }

    public void restart() {
        Log.v(TAG, this.getClass().getSimpleName() + " restart:"
                + " mApi=" + mApi
                + " mDeviceId=" + mDeviceId);
        if (mApi != null || mDeviceId == null || mDeviceId.isEmpty()) {
            return;
        }
        Toast.makeText(this,
                getString(R.string.connecting) + " " + mDeviceId,
                Toast.LENGTH_SHORT).show();
        if (mPlotListener != null) {
            mPlot.removeListener(mPlotListener);
            mPlotListener = null;
        }
        mPlotListener = new PlotListener() {
            @Override
            public void onBeforeDraw(Plot source, Canvas canvas) {
            }

            @Override
            public void onAfterDraw(Plot source, Canvas canvas) {
//                long now = new Date().getTime();
//                Log.d(TAG,
//                        "onAfterDraw: mOrientationChanged=" +
//                        mOrientationChanged +
//                                " deltaT=" + (now - redrawTime0));
//                redrawTime0 = now;
                if (mOrientationChanged) {
                    mOrientationChanged = false;
                    Log.d(TAG, "onAfterDraw: orientation changed");
                    setupPlot();
                    mPlotter.updatePlot(mPlot);
                }
            }
        };
        mPlot.addListener(mPlotListener);

        mApi = PolarBleApiDefaultImpl.defaultImplementation(this,
                PolarBleApi.FEATURE_POLAR_SENSOR_STREAMING |
                        PolarBleApi.FEATURE_BATTERY_INFO |
                        PolarBleApi.FEATURE_DEVICE_INFO |
                        PolarBleApi.FEATURE_HR);
        mApi.setApiCallback(new PolarBleApiCallback() {
            @Override
            public void blePowerStateChanged(boolean b) {
                Log.d(TAG, "*BluetoothStateChanged " + b);
            }

            @Override
            public void deviceConnected(PolarDeviceInfo s) {
                Log.d(TAG, "*Device connected " + s.deviceId);
                Toast.makeText(ECGActivity.this, R.string.connected,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void deviceDisconnected(PolarDeviceInfo s) {
                Log.d(TAG, "*Device disconnected " + s);
            }

            @Override
            public void ecgFeatureReady(String s) {
                Log.d(TAG, "*ECG Feature ready " + s);
                streamECG();
            }

            @Override
            public void hrFeatureReady(String s) {
                Log.d(TAG, "*HR Feature ready " + s);
            }

            @Override
            public void disInformationReceived(String s, UUID u, String s1) {
                if (u.equals(UUID.fromString("00002a28-0000-1000-8000" +
                        "-00805f9b34fb"))) {
                    mFirmware = s1.trim();
                    String msg = "Firmware: " + mFirmware;
                    Log.d(TAG, "*Firmware: " + s + " " + mFirmware);
                    mTextViewFW.append(msg + "\n");
                }
            }

            @Override
            public void batteryLevelReceived(String s, int i) {
                mBatteryLevel = Integer.toString(i);
                String msg = "ID: " + s + "\nBattery level: " + i;
                Log.d(TAG, "*Battery level " + s + " " + i);
                mTextViewFW.append(msg + "\n");
            }

            @Override
            public void hrNotificationReceived(String s,
                                               PolarHrData polarHrData) {
                if (mPlaying) {
                    Log.d(TAG,
                            "*HR " + polarHrData.hr + " mPlaying=" + mPlaying);
                    mTextViewHR.setText(String.valueOf(polarHrData.hr));
                }
            }
        });
        try {
            mApi.connectToDevice(mDeviceId);
            mPlaying = true;
        } catch (PolarInvalidArgument ex) {
            String msg = "connectToDevice: Bad argument: mDeviceId" + mDeviceId;
            Utils.excMsg(this, msg, ex);
            Log.d(TAG, "    restart: " + msg);
            mPlaying = false;
        }
        invalidateOptionsMenu();
        Log.d(TAG, "    restart(end) mApi=" + mApi + " mPlaying=" + mPlaying);
    }
}
