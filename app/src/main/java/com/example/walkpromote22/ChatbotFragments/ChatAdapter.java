package com.example.walkpromote22.ChatbotFragments;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.walkpromote22.R;
import java.util.List;
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {
    private final List<Message> messages;

    public ChatAdapter(List<Message> messages) {
        this.messages = messages;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView userMessage, botMessage;
        FrameLayout customContainer;

        public ViewHolder(View itemView) {
            super(itemView);
            userMessage = itemView.findViewById(R.id.user_message);
            botMessage = itemView.findViewById(R.id.bot_message);
            customContainer = itemView.findViewById(R.id.custom_container);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Message message = messages.get(position);

        if (message.isCustomView()) {
            // 隐藏文本视图
            holder.userMessage.setVisibility(View.GONE);
            holder.botMessage.setVisibility(View.GONE);
            holder.customContainer.setVisibility(View.VISIBLE);

            // 先清空容器
            holder.customContainer.removeAllViews();

            View customView = message.getCustomView();

            // 🛡 防止 “The specified child already has a parent” 崩溃
            if (customView.getParent() != null) {
                ((ViewGroup) customView.getParent()).removeView(customView);
            }

            holder.customContainer.addView(customView);

        } else {
            // 普通文本消息逻辑
            holder.customContainer.setVisibility(View.GONE);

            if (message.isUser()) {
                holder.userMessage.setVisibility(View.VISIBLE);
                holder.botMessage.setVisibility(View.GONE);
                holder.userMessage.setText(message.getText());
            } else {
                holder.botMessage.setVisibility(View.VISIBLE);
                holder.userMessage.setVisibility(View.GONE);
                holder.botMessage.setText(message.getText());
            }
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }
}
