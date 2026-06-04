package me.pompel.elauncher;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Set;

public class recyclerAdapter extends RecyclerView.Adapter<recyclerAdapter.AppViewHolder> {
    private final ArrayList<App> appList;
    private final RecyclerViewClickListener listener;
    private Set<String> processPackages;

    public recyclerAdapter(ArrayList<App> appList, Set<String> processPackages, RecyclerViewClickListener listener) {
        this.appList = appList;
        this.listener = listener;
        this.processPackages = processPackages;
    }

    public void setProcessPackages(Set<String> processPackages) {
        this.processPackages = processPackages;
    }

    public class AppViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {
        private final TextView nameText;

        @Override
        public void onClick(View view) { listener.onClick(appList.get(getAbsoluteAdapterPosition())); }

        @Override
        public boolean onLongClick(View view) {
            listener.onLongClick(appList.get(getAbsoluteAdapterPosition()));
            return true;
        }

        public AppViewHolder(final View view) {
            super(view);
            nameText = view.findViewById(R.id.app_name);
            view.setOnClickListener(this);
            view.setOnLongClickListener(this);
        }
    }

    @NonNull
    @Override
    public recyclerAdapter.AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new AppViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.list_items, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull recyclerAdapter.AppViewHolder holder, int position) {
        SpannableString appName = appList.get(position).appName;
        String packageId = appList.get(position).packageId;
        holder.nameText.setText(appName);

        // remove all the spans after the string has been set
        Object[] spans = appName.getSpans(0, appName.length(), Object.class);
        for (Object span : spans) {
            appName.removeSpan(span);
        }

        if (processPackages != null && processPackages.contains(packageId)) {
            appName.setSpan(new StyleSpan(Typeface.BOLD), 0, appName.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    public interface RecyclerViewClickListener {
        void onClick(App app);
        void onLongClick(App app);
    }
}
