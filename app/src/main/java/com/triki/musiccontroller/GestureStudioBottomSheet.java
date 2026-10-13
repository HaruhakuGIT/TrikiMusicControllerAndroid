package com.triki.musiccontroller;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.triki.musiccontroller.ble.TrikiBleManager;
import com.triki.musiccontroller.engine.GestureEngine;
import com.triki.musiccontroller.model.ImuSample;
import com.triki.musiccontroller.model.MusicAction;
import com.triki.musiccontroller.model.RecordedGesture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GestureStudioBottomSheet {

    public interface OnModelChangedListener {
        void onModelChanged(int totalGesturesCount, int trainedActionsCount);
    }

    private final Context context;
    private final GestureEngine gestureEngine;
    private final TrikiBleManager bleManager;
    private final OnModelChangedListener modelChangedListener;

    private BottomSheetDialog dialog;
    private GesturesAdapter adapter;
    private final List<RecordedGesture> recordedGestures = new ArrayList<>();

    // Selected Training Configuration
    private MusicAction selectedAction = MusicAction.NEXT_TRACK;
    private int recordingDurationSeconds = 7;

    // Recording State
    private enum StudioState { IDLE, COUNTDOWN, RECORDING }
    private StudioState currentState = StudioState.IDLE;

    private final List<ImuSample> sessionSamples = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long recordingStartTime = 0;
    private int countdownSecondsRemaining = 3;
    private double liveMaxG = 0;
    private double liveMaxGyro = 0;

    // UI View References
    private TextView txtRecordStatus;
    private TextView txtRecordCountdown;
    private ProgressBar pbRecordProgress;
    private TextView txtRecordSamplesCount;
    private TextView txtRecordLivePeakG;
    private TextView txtRecordLivePeakGyro;
    private MaterialButton btnRecord;
    private TextView txtHeader;
    private TextView txtEmptyList;

    public GestureStudioBottomSheet(Context context, GestureEngine gestureEngine, TrikiBleManager bleManager, OnModelChangedListener listener) {
        this.context = context;
        this.gestureEngine = gestureEngine;
        this.bleManager = bleManager;
        this.modelChangedListener = listener;
    }

    public void show() {
        dialog = new BottomSheetDialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_gesture_studio, null);
        dialog.setContentView(view);

        dialog.setOnShowListener(d -> {
            BottomSheetDialog bsd = (BottomSheetDialog) d;
            android.widget.FrameLayout bottomSheet = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior<android.widget.FrameLayout> behavior =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        });

        initViews(view);
        loadGesturesList();

        dialog.setOnDismissListener(d -> stopSessionIfRunning());
        dialog.show();
    }

    private void initViews(View v) {
        MaterialButton btnClose = v.findViewById(R.id.btnCloseStudio);
        btnClose.setOnClickListener(v1 -> dialog.dismiss());

        // Step 1: Action ChipGroup
        ChipGroup chipGroupAction = v.findViewById(R.id.chipGroupAction);
        chipGroupAction.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipPlayPause) selectedAction = MusicAction.PLAY_PAUSE;
            else if (id == R.id.chipNext) selectedAction = MusicAction.NEXT_TRACK;
            else if (id == R.id.chipPrev) selectedAction = MusicAction.PREVIOUS_TRACK;
            else if (id == R.id.chipVolUp) selectedAction = MusicAction.VOLUME_UP;
            else if (id == R.id.chipVolDown) selectedAction = MusicAction.VOLUME_DOWN;
            else if (id == R.id.chipIdle) selectedAction = MusicAction.NONE;
        });

        // Step 2: Duration ChipGroup
        ChipGroup chipGroupDuration = v.findViewById(R.id.chipGroupDuration);
        chipGroupDuration.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chipDur5) recordingDurationSeconds = 5;
            else if (id == R.id.chipDur7) recordingDurationSeconds = 7;
            else if (id == R.id.chipDur10) recordingDurationSeconds = 10;
        });

        // Step 2: Recording HUD
        txtRecordStatus = v.findViewById(R.id.txtRecordStatus);
        txtRecordCountdown = v.findViewById(R.id.txtRecordCountdown);
        pbRecordProgress = v.findViewById(R.id.pbRecordProgress);
        txtRecordSamplesCount = v.findViewById(R.id.txtRecordSamplesCount);
        txtRecordLivePeakG = v.findViewById(R.id.txtRecordLivePeakG);
        txtRecordLivePeakGyro = v.findViewById(R.id.txtRecordLivePeakGyro);
        btnRecord = v.findViewById(R.id.btnRecordGesture);

        btnRecord.setOnClickListener(v1 -> onRecordButtonClicked());

        // Step 3: List & Header
        txtHeader = v.findViewById(R.id.txtRecordedGesturesHeader);
        txtEmptyList = v.findViewById(R.id.txtEmptyGesturesList);
        MaterialButton btnClearAll = v.findViewById(R.id.btnClearAllGestures);

        btnClearAll.setOnClickListener(v1 -> confirmClearAll());

        RecyclerView recyclerView = v.findViewById(R.id.recyclerRecordedGestures);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        adapter = new GesturesAdapter();
        recyclerView.setAdapter(adapter);
    }

    private void onRecordButtonClicked() {
        if (currentState != StudioState.IDLE) {
            stopSessionIfRunning();
            return;
        }

        if (bleManager == null || bleManager.getState() != TrikiBleManager.ConnectionState.CONNECTED) {
            Toast.makeText(context, R.string.step2_no_data, Toast.LENGTH_SHORT).show();
            // Allow proceeding anyway for dry-run or testing, but show guidance
        }

        startCountdown();
    }

    private void startCountdown() {
        currentState = StudioState.COUNTDOWN;
        countdownSecondsRemaining = 3;
        btnRecord.setText(R.string.btn_stop);
        btnRecord.setBackgroundColor(Color.parseColor("#455A64"));
        txtRecordStatus.setTextColor(Color.parseColor("#00E5FF"));

        countdownRunnable.run();
    }

    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState != StudioState.COUNTDOWN) return;

            if (countdownSecondsRemaining > 0) {
                txtRecordStatus.setText(context.getString(R.string.step2_countdown, countdownSecondsRemaining));
                txtRecordCountdown.setText(countdownSecondsRemaining + "s");
                pbRecordProgress.setProgress((3 - countdownSecondsRemaining) * 33);
                countdownSecondsRemaining--;
                handler.postDelayed(this, 1000);
            } else {
                startRecording();
            }
        }
    };

    private void startRecording() {
        currentState = StudioState.RECORDING;
        sessionSamples.clear();
        recordingStartTime = System.currentTimeMillis();
        liveMaxG = 0;
        liveMaxGyro = 0;

        btnRecord.setText(R.string.step2_btn_recording);
        btnRecord.setBackgroundColor(Color.parseColor("#FF1744"));
        txtRecordStatus.setText("🔴 " + context.getString(R.string.step2_recording_hint));
        txtRecordStatus.setTextColor(Color.parseColor("#FF5252"));
        pbRecordProgress.setProgress(0);

        recordingProgressRunnable.run();
    }

    private final Runnable recordingProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentState != StudioState.RECORDING) return;

            long elapsed = System.currentTimeMillis() - recordingStartTime;
            long total = recordingDurationSeconds * 1000L;

            if (elapsed >= total) {
                finishRecording();
            } else {
                int progress = (int) ((elapsed * 100) / total);
                pbRecordProgress.setProgress(progress);
                txtRecordCountdown.setText(String.format(Locale.US, "%.1fs / %ds", elapsed / 1000.0f, recordingDurationSeconds));
                handler.postDelayed(this, 60);
            }
        }
    };

    private void finishRecording() {
        currentState = StudioState.IDLE;
        btnRecord.setText(R.string.step2_btn_record);
        btnRecord.setBackgroundColor(Color.parseColor("#D32F2F"));
        pbRecordProgress.setProgress(100);

        if (sessionSamples.isEmpty()) {
            txtRecordStatus.setText(context.getString(R.string.step2_no_data));
            txtRecordStatus.setTextColor(Color.parseColor("#EF5350"));
            return;
        }

        String actionName = getActionDisplayName(selectedAction);
        List<RecordedGesture> segmented = RecordedGesture.segmentRepetitions(actionName, selectedAction, sessionSamples);

        for (RecordedGesture g : segmented) {
            gestureEngine.getRepository().saveRecording(g);
        }

        gestureEngine.trainFromSavedRecordings(context);

        txtRecordStatus.setText("✅ " + context.getString(R.string.step2_completed, segmented.size()));
        txtRecordStatus.setTextColor(Color.parseColor("#00E676"));
        Toast.makeText(context, context.getString(R.string.gesture_saved_toast, segmented.size()), Toast.LENGTH_SHORT).show();

        loadGesturesList();

        if (modelChangedListener != null) {
            modelChangedListener.onModelChanged(gestureEngine.getTotalRecordingsCount(), gestureEngine.getProfileCount());
        }
    }

    public void onSampleReceived(ImuSample sample) {
        if (currentState != StudioState.RECORDING) return;

        sessionSamples.add(sample);
        double aMag = sample.getAccelMagnitude();
        double gMag = sample.getGyroMagnitude();

        if (aMag > liveMaxG) liveMaxG = aMag;
        if (gMag > liveMaxGyro) liveMaxGyro = gMag;

        handler.post(() -> {
            txtRecordSamplesCount.setText("Próbki: " + sessionSamples.size());
            txtRecordLivePeakG.setText(String.format(Locale.US, "Max: %.2f G", liveMaxG));
            txtRecordLivePeakGyro.setText(String.format(Locale.US, "Gyro: %.0f °/s", liveMaxGyro));
        });
    }

    private void stopSessionIfRunning() {
        handler.removeCallbacks(countdownRunnable);
        handler.removeCallbacks(recordingProgressRunnable);
        currentState = StudioState.IDLE;
        if (btnRecord != null) {
            btnRecord.setText(R.string.step2_btn_record);
            btnRecord.setBackgroundColor(Color.parseColor("#D32F2F"));
        }
    }

    private void loadGesturesList() {
        recordedGestures.clear();
        recordedGestures.addAll(gestureEngine.getRepository().loadAllRecordings());
        adapter.notifyDataSetChanged();

        int count = recordedGestures.size();
        txtHeader.setText(context.getString(R.string.step3_title, count));

        if (count == 0) {
            txtEmptyList.setVisibility(View.VISIBLE);
        } else {
            txtEmptyList.setVisibility(View.GONE);
        }
    }

    private void confirmDeleteIndividualGesture(RecordedGesture gesture, int position) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.btn_delete_confirm_title)
                .setMessage(context.getString(R.string.btn_delete_confirm_msg) + "\n\n[" + gesture.fileName + "]")
                .setPositiveButton(R.string.btn_delete_confirm_ok, (d, w) -> {
                    boolean deleted = gestureEngine.deleteRecordingAndRetrain(context, gesture.fileName);
                    if (deleted) {
                        recordedGestures.remove(position);
                        adapter.notifyItemRemoved(position);
                        adapter.notifyItemRangeChanged(position, recordedGestures.size());

                        int count = recordedGestures.size();
                        txtHeader.setText(context.getString(R.string.step3_title, count));
                        if (count == 0) txtEmptyList.setVisibility(View.VISIBLE);

                        Toast.makeText(context, R.string.gesture_deleted_toast, Toast.LENGTH_SHORT).show();

                        if (modelChangedListener != null) {
                            modelChangedListener.onModelChanged(gestureEngine.getTotalRecordingsCount(), gestureEngine.getProfileCount());
                        }
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(context)
                .setTitle(R.string.btn_delete_all)
                .setMessage(R.string.btn_delete_all_confirm_msg)
                .setPositiveButton(R.string.btn_delete_confirm_ok, (d, w) -> {
                    gestureEngine.getRepository().clearAll();
                    gestureEngine.trainFromSavedRecordings(context);
                    loadGesturesList();
                    if (modelChangedListener != null) {
                        modelChangedListener.onModelChanged(gestureEngine.getTotalRecordingsCount(), gestureEngine.getProfileCount());
                    }
                    Toast.makeText(context, "Wyczyszczono wszystkie gesty.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private String getActionDisplayName(MusicAction action) {
        if (action == null) return context.getString(R.string.action_idle);
        switch (action) {
            case PLAY_PAUSE: return context.getString(R.string.action_play_pause);
            case NEXT_TRACK: return context.getString(R.string.action_next);
            case PREVIOUS_TRACK: return context.getString(R.string.action_prev);
            case VOLUME_UP: return context.getString(R.string.action_vol_up);
            case VOLUME_DOWN: return context.getString(R.string.action_vol_down);
            default: return context.getString(R.string.action_idle);
        }
    }

    // RecyclerView Adapter
    private class GesturesAdapter extends RecyclerView.Adapter<GesturesAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recorded_gesture, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            RecordedGesture g = recordedGestures.get(position);
            MusicAction act = g.getMusicAction();

            holder.txtName.setText(getActionDisplayName(act));
            holder.txtDate.setText(g.fileName != null ? g.fileName : g.recordedAt);

            holder.txtPillAccel.setText(String.format(Locale.US, "%.2f G", g.peakAccelG));
            holder.txtPillGyro.setText(String.format(Locale.US, "%.0f °/s", g.peakGyroDegPerSec));
            holder.txtPillDuration.setText(String.format(Locale.US, "%.0f ms", g.durationMs));

            // Color & Icon
            switch (act) {
                case PLAY_PAUSE:
                    holder.txtIcon.setText("⏯");
                    holder.cardIcon.setStrokeColor(Color.parseColor("#00E676"));
                    break;
                case NEXT_TRACK:
                    holder.txtIcon.setText("⏭");
                    holder.cardIcon.setStrokeColor(Color.parseColor("#00E5FF"));
                    break;
                case PREVIOUS_TRACK:
                    holder.txtIcon.setText("⏮");
                    holder.cardIcon.setStrokeColor(Color.parseColor("#00E5FF"));
                    break;
                case VOLUME_UP:
                    holder.txtIcon.setText("🔊");
                    holder.cardIcon.setStrokeColor(Color.parseColor("#2979FF"));
                    break;
                case VOLUME_DOWN:
                    holder.txtIcon.setText("🔉");
                    holder.cardIcon.setStrokeColor(Color.parseColor("#2979FF"));
                    break;
                default:
                    holder.txtIcon.setText("🤫");
                    holder.cardIcon.setStrokeColor(Color.parseColor("#FFD54F"));
                    break;
            }

            // INDIVIDUAL DELETE BUTTON
            holder.btnDelete.setOnClickListener(v -> confirmDeleteIndividualGesture(g, holder.getAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return recordedGestures.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView cardIcon;
            TextView txtIcon;
            TextView txtName;
            TextView txtDate;
            TextView txtPillAccel;
            TextView txtPillGyro;
            TextView txtPillDuration;
            ImageButton btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                cardIcon = itemView.findViewById(R.id.cardActionIcon);
                txtIcon = itemView.findViewById(R.id.txtGestureIcon);
                txtName = itemView.findViewById(R.id.txtGestureActionName);
                txtDate = itemView.findViewById(R.id.txtGestureDate);
                txtPillAccel = itemView.findViewById(R.id.txtPillAccel);
                txtPillGyro = itemView.findViewById(R.id.txtPillGyro);
                txtPillDuration = itemView.findViewById(R.id.txtPillDuration);
                btnDelete = itemView.findViewById(R.id.btnDeleteGesture);
            }
        }
    }
}
