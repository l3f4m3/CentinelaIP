package com.fm.centinelaip;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public final class EventsActivity extends Activity {
    private EventStore eventStore;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_events);
        eventStore = new EventStore(this);
        container = findViewById(R.id.eventsContainer);
        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        findViewById(R.id.clearButton).setOnClickListener(view -> confirmClear());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderEvents();
    }

    private void renderEvents() {
        container.removeAllViews();
        List<EventRecord> records = eventStore.load();
        if (records.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.empty_events);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(15f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(28), dp(80), dp(28), dp(28));
            container.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (EventRecord record : records) {
            container.addView(createCard(record));
            Space space = new Space(this);
            container.addView(space, new LinearLayout.LayoutParams(1, dp(12)));
        }
    }

    private View createCard(EventRecord record) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(12));
        card.setBackgroundResource(R.drawable.bg_card);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        Bitmap bitmap = BitmapFactory.decodeFile(eventStore.imageFile(record).getAbsolutePath(), options);
        image.setImageBitmap(bitmap);
        card.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(210)));

        TextView title = new TextView(this);
        title.setText(record.summary);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(6), dp(12), dp(6), 0);
        card.addView(title);

        String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(record.timestamp));
        TextView details = new TextView(this);
        details.setText(record.cameraName + " · " + date);
        details.setTextColor(getColor(R.color.text_secondary));
        details.setTextSize(12f);
        details.setPadding(dp(6), dp(4), dp(6), 0);
        card.addView(details);
        return card;
    }

    private void confirmClear() {
        if (eventStore.load().isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Eliminar eventos")
                .setMessage("Se borrarán las capturas y el historial local. Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> {
                    eventStore.clear();
                    renderEvents();
                })
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
