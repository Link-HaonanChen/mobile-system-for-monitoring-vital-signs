package com.grp.application.GRPNotification;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import com.grp.application.R;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * This class is to send notification wo user.
 * It create notification channel which enables to send notification to user.
 *
 * @author UNNC GRP G19
 */

public class GRPNotification {

    private static GRPNotification GRPNotification;
    private String CHANNEL_ID = "CHANNEL_ID";
    private int notificationId = 1;

    public static GRPNotification getInstance(Context context) {
        if (GRPNotification == null) {
            GRPNotification = new GRPNotification();
            NotificationManager notificationManager = (NotificationManager) context.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        }
        return GRPNotification;
    }

    /**
     * This method is to create a channel to send notification to user.
     *
     * @param context The app context
     */
    private void createNotificationChannel(Context context) {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "CHANNELNAME";
            String description = "CHANNELD";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * This method is to send user notification if the user does not wear the device properly.
     *
     * @param context The app context
     */
    public void sendMsgOnNotWearDevice(Context context) {
        createNotificationChannel(context);
        String message = "The device has not worn properly!";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_message)
                .setContentTitle("Warning!")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.notify(1, builder.build());
    }

    /**
     * This method is to send user notification if the system does not capture data.
     *
     * @param context The app context
     */
    public void sendMsgOnNotCaptureData(Context context) {
        createNotificationChannel(context);
        String message = "The data is not captured";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_message)
                .setContentTitle("Warning!")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.notify(2, builder.build());
    }

    /**
     * This method is to send user notification if the report is generated.
     *
     * @param context The app context
     */
    public void sendMsgOnReportGenerated(Context context) {
        createNotificationChannel(context);
        String message = "A report is generated";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_message)
                .setContentTitle("Report is Ready")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.notify(3, builder.build());
    }

    /**
     * This method is to send user notification if the heart rate is too high.
     *
     * @param context The app context
     */
    public void sendMsgOnHighHR(Context context) {
        createNotificationChannel(context);
        String message = "Your heart rate is too high. Take a rest!";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_message)
                .setContentTitle("Alert!")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        notificationManager.notify(4, builder.build());
    }
}
