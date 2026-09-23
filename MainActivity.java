package com.billy.mytimesheet;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private final TimeZone qatar = TimeZone.getTimeZone("Asia/Qatar");
    private final Handler handler = new Handler();
    private SharedPreferences prefs;
    private TextView clock, date, status, records;
    private String pendingExportHtml = null;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateClock();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("timesheet_data", MODE_PRIVATE);

        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        status = findViewById(R.id.status);
        records = findViewById(R.id.records);

        Button in = findViewById(R.id.punchIn);
        Button out = findViewById(R.id.punchOut);
        Button export = findViewById(R.id.exportWord);

        in.setOnClickListener(v -> punchIn());
        out.setOnClickListener(v -> punchOut());
        export.setOnClickListener(v -> exportMonth());

        refresh();
        ticker.run();
    }

    private SimpleDateFormat fmt(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(qatar);
        return f;
    }

    private String today() { return fmt("yyyy-MM-dd").format(new Date()); }
    private String nowStamp() { return fmt("yyyy-MM-dd HH:mm:ss").format(new Date()); }
    private String currentMonth() { return fmt("yyyy-MM").format(new Date()); }

    private JSONArray loadRows() {
        try { return new JSONArray(prefs.getString("rows", "[]")); }
        catch(Exception e) { return new JSONArray(); }
    }

    private void saveRows(JSONArray a) {
        prefs.edit().putString("rows", a.toString()).apply();
    }

    private JSONObject findToday(JSONArray a) {
        for(int i=0;i<a.length();i++) {
            JSONObject o = a.optJSONObject(i);
            if(o != null && today().equals(o.optString("date"))) return o;
        }
        return null;
    }

    private void punchIn() {
        try {
            JSONArray a = loadRows();
            JSONObject r = findToday(a);
            if(r != null && !r.optString("in").isEmpty()) {
                toast("Already punched in today.");
                return;
            }
            if(r == null) {
                r = new JSONObject();
                r.put("date", today());
                r.put("in", "");
                r.put("out", "");
                a.put(r);
            }
            r.put("in", nowStamp());
            saveRows(a);
            refresh();
        } catch(Exception e) { toast("Could not save Punch In."); }
    }

    private void punchOut() {
        try {
            JSONArray a = loadRows();
            JSONObject r = findToday(a);
            if(r == null || r.optString("in").isEmpty()) {
                toast("Punch In first.");
                return;
            }
            if(!r.optString("out").isEmpty()) {
                toast("Already punched out today.");
                return;
            }
            r.put("out", nowStamp());
            saveRows(a);
            refresh();
        } catch(Exception e) { toast("Could not save Punch Out."); }
    }

    private String timeOnly(String stamp) {
        if(stamp == null || stamp.isEmpty()) return "-";
        int i = stamp.indexOf(' ');
        return i >= 0 ? stamp.substring(i+1) : stamp;
    }

    private double hours(String a, String b) {
        if(a == null || b == null || a.isEmpty() || b.isEmpty()) return -1;
        try {
            SimpleDateFormat f = fmt("yyyy-MM-dd HH:mm:ss");
            long diff = f.parse(b).getTime() - f.parse(a).getTime();
            if(diff < 0) diff += 24L*60*60*1000;
            return diff / 3600000.0;
        } catch(Exception e) { return -1; }
    }

    private void refresh() {
        JSONArray a = loadRows();
        JSONObject r = findToday(a);

        Button in = findViewById(R.id.punchIn);
        Button out = findViewById(R.id.punchOut);

        if(r == null || r.optString("in").isEmpty()) {
            status.setText("No punch yet today.");
            in.setEnabled(true);
            out.setEnabled(false);
        } else if(r.optString("out").isEmpty()) {
            status.setText("Punched in: " + timeOnly(r.optString("in")) + " • On duty");
            in.setEnabled(false);
            out.setEnabled(true);
        } else {
            double h = hours(r.optString("in"), r.optString("out"));
            status.setText("Completed: " + timeOnly(r.optString("in")) + " - " +
                    timeOnly(r.optString("out")) + " • " + String.format(Locale.US,"%.2f hrs",h));
            in.setEnabled(false);
            out.setEnabled(false);
        }

        List<JSONObject> list = new ArrayList<>();
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);
            if(o!=null && o.optString("date").startsWith(currentMonth())) list.add(o);
        }
        list.sort((x,y)->y.optString("date").compareTo(x.optString("date")));

        StringBuilder sb = new StringBuilder();
        for(JSONObject o:list) {
            double h=hours(o.optString("in"),o.optString("out"));
            sb.append(o.optString("date")).append("\n")
              .append("In: ").append(timeOnly(o.optString("in")))
              .append("   Out: ").append(timeOnly(o.optString("out")))
              .append("   Hours: ").append(h<0?"-":String.format(Locale.US,"%.2f",h))
              .append("\n\n");
        }
        records.setText(sb.length()==0 ? "No records yet." : sb.toString());
    }

    private void updateClock() {
        clock.setText(fmt("HH:mm:ss").format(new Date()));
        date.setText(fmt("EEEE, MMMM d, yyyy").format(new Date()));
    }

    private void exportMonth() {
        JSONArray a = loadRows();
        List<JSONObject> list = new ArrayList<>();
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);
            if(o!=null && o.optString("date").startsWith(currentMonth())) list.add(o);
        }
        if(list.isEmpty()){ toast("No records for this month."); return; }
        list.sort(Comparator.comparing(x -> x.optString("date")));

        double total=0;
        StringBuilder rowsHtml=new StringBuilder();
        for(JSONObject o:list){
            double h=hours(o.optString("in"),o.optString("out"));
            if(h>0) total+=h;
            rowsHtml.append("<tr><td>").append(o.optString("date")).append("</td><td>")
                    .append(timeOnly(o.optString("in"))).append("</td><td>")
                    .append(timeOnly(o.optString("out"))).append("</td><td>")
                    .append(h<0?"":String.format(Locale.US,"%.2f",h)).append("</td></tr>");
        }

        pendingExportHtml =
            "<html><head><meta charset='utf-8'><style>"+
            "body{font-family:Arial}table{border-collapse:collapse;width:100%}"+
            "th,td{border:1px solid #000;padding:6px;text-align:center}th{background:#eee}"+
            "</style></head><body><h2 style='text-align:center'>Monthly Timesheet</h2>"+
            "<p><b>Month:</b> "+currentMonth()+"<br><b>Timezone:</b> Asia/Qatar</p>"+
            "<table><tr><th>Date</th><th>Time In</th><th>Time Out</th><th>Total Hours</th></tr>"+
            rowsHtml+"</table><p><b>Total Recorded Hours: "+String.format(Locale.US,"%.2f",total)+
            "</b></p></body></html>";

        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/msword");
        i.putExtra(Intent.EXTRA_TITLE, "Timesheet_"+currentMonth()+".doc");
        startActivityForResult(i, 1001);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==1001 && resultCode==RESULT_OK && data!=null && pendingExportHtml!=null) {
            Uri uri=data.getData();
            try(OutputStream os=getContentResolver().openOutputStream(uri)) {
                os.write(("\uFEFF"+pendingExportHtml).getBytes(StandardCharsets.UTF_8));
                os.flush();
                toast("Word timesheet saved.");
            } catch(Exception e) {
                toast("Could not save Word file.");
            }
            pendingExportHtml=null;
        }
    }

    private void toast(String s) {
        Toast.makeText(this,s,Toast.LENGTH_SHORT).show();
    }
}
