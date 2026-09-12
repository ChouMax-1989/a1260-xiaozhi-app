package io.github.choumax.a1260xiaozhi.chat;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

public final class ChatBubbleAdapter extends BaseAdapter {
    private final Context context; private final List<ChatMessage> messages;
    public ChatBubbleAdapter(Context context, List<ChatMessage> messages) { this.context = context; this.messages = messages; }
    @Override public int getCount() { return messages.size(); }
    @Override public ChatMessage getItem(int position) { return messages.get(position); }
    @Override public long getItemId(int position) { return position; }
    private static final class Holder {
        final TextView bubble; final GradientDrawable background;
        String text;
        Holder(TextView bubble, GradientDrawable background) { this.bubble=bubble; this.background=background; }
    }
    @Override public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout row; Holder holder;
        if (convertView instanceof LinearLayout && convertView.getTag() instanceof Holder) {
            row=(LinearLayout)convertView; holder=(Holder)row.getTag();
        } else {
            row=new LinearLayout(context); row.setPadding(0,dp(4),0,dp(4));
            TextView bubble=new TextView(context); bubble.setTextSize(16); bubble.setLineSpacing(0,1.08f);
            bubble.setMaxWidth((int)(context.getResources().getDisplayMetrics().widthPixels*0.76f));
            bubble.setPadding(dp(12),dp(9),dp(12),dp(9));
            GradientDrawable background=new GradientDrawable(); background.setCornerRadius(dp(14)); bubble.setBackground(background);
            row.addView(bubble,new LinearLayout.LayoutParams(-2,-2)); holder=new Holder(bubble,background); row.setTag(holder);
        }
        ChatMessage message=getItem(position); boolean user=message.role==ChatMessage.Role.USER;
        row.setGravity(user?Gravity.END:Gravity.START);
        holder.bubble.setTextColor(user?Color.WHITE:Color.rgb(35,43,48));
        holder.background.setColor(user?Color.rgb(23,137,92):Color.WHITE);
        if(!message.text.equals(holder.text)){holder.bubble.setText(message.text);holder.text=message.text;}
        return row;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
