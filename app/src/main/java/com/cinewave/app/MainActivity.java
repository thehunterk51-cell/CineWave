package com.cinewave.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final String MOVIES_URL="https://azmovies.to/";
    private static final String SERIES_URL="https://noxx.gg/";
    private static final String UPDATE_URL="https://raw.githubusercontent.com/thehunterk51-cell/CineWave/main/version.json";
    private static final String RELEASES_URL="https://github.com/thehunterk51-cell/CineWave/releases/latest";
    private static final String PREFS="cinewave_settings", PREF_ADBLOCK="ad_block_enabled";
    private static final int APP_VERSION_CODE = 3;
    private static final Set<String> AD_HOSTS=new HashSet<>(Arrays.asList(
        "doubleclick.net","googlesyndication.com","googleadservices.com","adservice.google.com",
        "adnxs.com","adsrvr.org","advertising.com","criteo.com","taboola.com","outbrain.com",
        "popads.net","popcash.net","propellerads.com","exoclick.com","trafficjunky.net",
        "juicyads.com","adsterra.com","hilltopads.net"));
    private LinearLayout root;
    private WebView webView;
    private Button adBlockButton;
    private boolean adBlockEnabled=true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        adBlockEnabled=getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean(PREF_ADBLOCK,true);
        showHome();
        checkForUpdate(false);
    }
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String value,int size,int color){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        t.setGravity(Gravity.CENTER);return t;
    }
    private Button button(String label,int color){
        Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);b.setAllCaps(false);
        b.setTextSize(16);b.setFocusable(true);b.setFocusableInTouchMode(true);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        b.setOnFocusChangeListener((v,f)->{v.setScaleX(f?1.04f:1f);v.setScaleY(f?1.04f:1f);v.setElevation(f?dp(8):0);});
        return b;
    }
    private LinearLayout newRoot(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(22),dp(18),dp(22),dp(18));l.setBackgroundColor(Color.rgb(15,17,29));l.setFocusableInTouchMode(true);return l;
    }
    private void toggleAdBlock(){
        adBlockEnabled=!adBlockEnabled;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean(PREF_ADBLOCK,adBlockEnabled).apply();
        updateAdButton();Toast.makeText(this,adBlockEnabled?"Ad blocking enabled":"Ad blocking disabled",Toast.LENGTH_SHORT).show();
        if(webView!=null)webView.reload();
    }
    private void updateAdButton(){if(adBlockButton!=null)adBlockButton.setText(adBlockEnabled?"Ad blocking: ON ✓":"Ad blocking: OFF");}
    private void showHome(){
        destroyWebView();
        root=newRoot();
        TextView logo=text("🎬  CineWave",30,Color.WHITE);logo.setTypeface(null,1);
        root.addView(logo,new LinearLayout.LayoutParams(-1,dp(66)));
        root.addView(text("Movies and series in one place",17,Color.rgb(190,195,215)),new LinearLayout.LayoutParams(-1,dp(44)));
        Button movies=button("🎞  Movies  •  AZMovies",Color.rgb(103,58,183));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(60));bp.setMargins(0,dp(10),0,dp(8));root.addView(movies,bp);
        movies.setOnClickListener(v->openSite("Movies",MOVIES_URL));
        Button series=button("📺  Series  •  NOXX",Color.rgb(0,119,145));root.addView(series,new LinearLayout.LayoutParams(-1,dp(60)));
        series.setOnClickListener(v->openSite("Series",SERIES_URL));
        adBlockButton=button("",Color.rgb(54,60,80));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(54));
        ap.setMargins(0,dp(14),0,dp(7));root.addView(adBlockButton,ap);updateAdButton();adBlockButton.setOnClickListener(v->toggleAdBlock());
        Button update=button("Check for updates",Color.rgb(54,60,80));root.addView(update,new LinearLayout.LayoutParams(-1,dp(52)));
        update.setOnClickListener(v->checkForUpdate(true));
        Button saved=button("☆  Watch later",Color.rgb(54,60,80));root.addView(saved,new LinearLayout.LayoutParams(-1,dp(52)));
        saved.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Watch later")
            .setMessage("A saved list is not available in this version. Video downloads are not provided.")
            .setPositiveButton("OK",null).show());
        TextView note=text("For phones, Android TV and TV boxes. Some websites may not fully support WebView or remote navigation.",13,Color.rgb(160,165,185));
        note.setPadding(dp(4),dp(12),dp(4),dp(4));root.addView(note,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);root.requestFocus();
    }
    private void openSite(String title,String url){
        destroyWebView();root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(15,17,29));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(4),dp(8),dp(4));
        Button back=button("Back",Color.rgb(54,60,80));bar.addView(back,new LinearLayout.LayoutParams(dp(82),dp(46)));back.setOnClickListener(v->showHome());
        TextView heading=text(title,17,Color.WHITE);heading.setTypeface(null,1);bar.addView(heading,new LinearLayout.LayoutParams(0,dp(46),1));
        Button block=button(adBlockEnabled?"Block ads ✓":"Block ads ✕",Color.rgb(103,58,183));
        bar.addView(block,new LinearLayout.LayoutParams(dp(112),dp(46)));
        block.setOnClickListener(v->{toggleAdBlock();block.setText(adBlockEnabled?"Block ads ✓":"Block ads ✕");});
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(56)));
        webView=new WebView(this);webView.setFocusable(true);webView.setFocusableInTouchMode(true);
        WebSettings ws=webView.getSettings();ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);ws.setSupportMultipleWindows(false);ws.setJavaScriptCanOpenWindowsAutomatically(false);
        webView.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                String scheme=req.getUrl().getScheme();return scheme!=null&&!(scheme.equals("http")||scheme.equals("https"));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){
                if(adBlockEnabled&&isBlocked(req.getUrl().toString()))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                return super.shouldInterceptRequest(view,req);
            }
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView view,boolean dialog,boolean userGesture,android.os.Message resultMsg){
                // Do not allow unsolicited popup windows; keep navigation in the current WebView.
                return false;
            }
        });
        root.addView(webView,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);webView.loadUrl(url);webView.requestFocus();
    }
    private boolean isBlocked(String value){
        try{String host=new URI(value).getHost();if(host==null)return false;host=host.toLowerCase(Locale.ROOT);
            for(String domain:AD_HOSTS)if(host.equals(domain)||host.endsWith("."+domain))return true;
        }catch(Exception ignored){}return false;
    }
    private void checkForUpdate(boolean manual){
        Thread t=new Thread(()->{
            try{
                HttpURLConnection c=(HttpURLConnection)new URL(UPDATE_URL).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);
                c.setRequestProperty("Cache-Control","no-cache");
                BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));StringBuilder body=new StringBuilder();String line;
                while((line=r.readLine())!=null)body.append(line);r.close();c.disconnect();
                JSONObject json=new JSONObject(body.toString());int code=json.optInt("versionCode",0);
                String name=json.optString("versionName","new version"),link=json.optString("apkUrl",RELEASES_URL);
                runOnUiThread(()->{
                    if(code>APP_VERSION_CODE)new AlertDialog.Builder(this).setTitle("Update available")
                        .setMessage("CineWave "+name+" is available. Open the release page to download it?")
                        .setNegativeButton("Later",null).setPositiveButton("Open download",(d,w)->{
                            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(link)));}
                            catch(Exception e){Toast.makeText(this,"Could not open download page.",Toast.LENGTH_LONG).show();}
                        }).show();
                    else if(manual)Toast.makeText(this,"CineWave is up to date.",Toast.LENGTH_SHORT).show();
                });
            }catch(Exception e){if(manual)runOnUiThread(()->Toast.makeText(this,"Update check failed. Try again later.",Toast.LENGTH_LONG).show());}
        });t.setDaemon(true);t.start();
    }
    private void destroyWebView(){if(webView!=null){webView.stopLoading();webView.destroy();webView=null;}}
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else showHome();}
    @Override protected void onDestroy(){destroyWebView();super.onDestroy();}
}