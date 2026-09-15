package com.example.bus

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Filter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var autoComplete: AutoCompleteTextView
    private lateinit var result: TextView
    private lateinit var webView: WebView
    private lateinit var renameBar: LinearLayout
    private lateinit var renameInput: EditText
    private lateinit var renameSave: Button
    private lateinit var favButton: Button
    private lateinit var refreshAll: Button
    private lateinit var favList: RecyclerView
    private lateinit var favContainer: FrameLayout
    private lateinit var settingsButton: Button
    private lateinit var updateHint: TextView

    private lateinit var favAdapter: FavAdapter
    private val favItems = mutableListOf<String>()

    private var workers: MutableList<FavWorker>? = null

    private var parsed = false
    private var mode = "search"
    private var currentStopId: String = ""
    private var currentStopUrl: String = ""
    private var lastBackPress = 0L

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val prefs by lazy { getSharedPreferences("stops", MODE_PRIVATE) }

    private val suggestions = mutableListOf<Pair<String, String>>()
    private val suggestionUrls = mutableListOf<String>()
    private lateinit var adapter: SuggestAdapter

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("bus", "=== onCreate ===")

        // 1) Только UI — ничего тяжёлого до первого кадра
        setContentView(R.layout.activity_main)

        autoComplete = findViewById(R.id.autoComplete)
        result = findViewById(R.id.resultText)
        webView = findViewById(R.id.webView)
        renameBar = findViewById(R.id.renameBar)
        renameInput = findViewById(R.id.renameInput)
        renameSave = findViewById(R.id.renameSave)
        favButton = findViewById(R.id.favButton)
        refreshAll = findViewById(R.id.refreshAll)
        favList = findViewById(R.id.favList)
        favContainer = findViewById(R.id.favContainer)
        settingsButton = findViewById(R.id.settingsButton)
        updateHint = findViewById(R.id.updateHint)

        settingsButton.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        updateHint.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        adapter = SuggestAdapter(this, suggestions)
        autoComplete.setAdapter(adapter)
        autoComplete.threshold = 1

        favAdapter = FavAdapter(favItems,
            onClick = { id ->
                val url = prefs.getString("url_$id", null)
                val name = prefs.getString("name_$id", null)
                    ?: prefs.getString("origName_$id", null) ?: id
                Log.d("bus", "fav onClick id=$id url=$url")
                if (url != null) openStop(url, name)
                else Toast.makeText(this, "URL не сохранён", Toast.LENGTH_SHORT).show()
            },
            onDelete = { id ->
                val favs = getFavorites()
                favs.remove(id)
                prefs.edit().putString("favorites", favs.joinToString(",")).apply()
                Toast.makeText(this, "Удалено", Toast.LENGTH_SHORT).show()
                renderFavorites()
            },
            onOrderChanged = { newOrder ->
                prefs.edit().putString("favorites", newOrder.joinToString(",")).apply()
            }
        )
        favList.layoutManager = LinearLayoutManager(this)
        favList.adapter = favAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = t.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                Collections.swap(favItems, from, to)
                favAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val id = favItems.removeAt(pos)
                favAdapter.notifyItemRemoved(pos)
                val favs = getFavorites()
                favs.remove(id)
                prefs.edit().putString("favorites", favs.joinToString(",")).apply()
                renderFavorites()
                Toast.makeText(this@MainActivity, "Удалено", Toast.LENGTH_SHORT).show()
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                prefs.edit().putString("favorites", favItems.joinToString(",")).apply()
            }
        })
        touchHelper.attachToRecyclerView(favList)

        renameSave.setOnClickListener {
            val newName = renameInput.text.toString().trim()
            if (newName.isNotEmpty() && currentStopId.isNotEmpty()) {
                prefs.edit().putString("name_$currentStopId", newName).apply()
                updateFavButton()
                Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            }
        }

        favButton.setOnClickListener { toggleFavorite() }
        refreshAll.setOnClickListener { startUpdateAll() }

        autoComplete.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                searchStops(autoComplete.text.toString())
                true
            } else false
        }

        autoComplete.setOnItemClickListener { _, _, position, _ ->
            val url = suggestionUrls.getOrNull(position)
            val title = suggestions.getOrNull(position)?.first.orEmpty()
            Log.d("bus", "dropdown click pos=$position title=$title url=$url")
            openStop(url, title)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == "stop") {
                    showMainView()
                    lastBackPress = 0
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2000) {
                    finish()
                } else {
                    lastBackPress = now
                    Toast.makeText(this@MainActivity, "Нажмите ещё раз для выхода", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Показываем список избранного СРАЗУ, без ожидания воркеров
        showMainView()

        // 2) Всё тяжёлое — ПОСЛЕ первого кадра UI
        Handler(Looper.getMainLooper()).post {
            Log.d("bus", "post-frame: starting heavy init")

            // DNS warmup в фоне (не блокирует UI)
            Thread {
                try {
                    val t0 = System.currentTimeMillis()
                    InetAddress.getByName("yandex.ru")
                    InetAddress.getByName("yandex.net")
                    Log.d("bus", "DNS warmup done in ${System.currentTimeMillis() - t0}ms")
                } catch (e: Exception) {
                    Log.w("bus", "DNS warmup fail: ${e.message}")
                }
            }.start()

            // Warm-up Chromium (создаёт WebView, ~50-100мс)
            warmUpWebView()

            // Прокси + основной WebView — тоже после UI
            setupWebViewNoProxy()
            setupWebView(webView)

            webView.addJavascriptInterface(object {
                @JavascriptInterface fun onSuggest(json: String) { Log.d("bus", "JS onSuggest raw=$json"); runOnUiThread { showSuggestions(json) } }
                @JavascriptInterface fun onParsed(json: String) { Log.d("bus", "JS onParsed raw=$json"); runOnUiThread { handleStopParsed(json) } }
                @JavascriptInterface fun onDebug(s: String) { Log.d("bus", "DBG:\n$s") }
            }, "Android")

            // Динамическое число воркеров
            val favCount = getFavorites().size
            val workerCount = minOf(maxOf(favCount, 1), 6)
            workers = (0 until workerCount).map { FavWorker(it) }.toMutableList()
            Log.d("bus", "workers pre-created: $workerCount (favs=$favCount)")

            // Старт обновления — ещё чуть позже, чтобы UI успел устояться
            Handler(Looper.getMainLooper()).postDelayed({ startUpdateAll() }, 100)
        }
    }

    private fun warmUpWebView() {
        try {
            val wv = WebView(this)
            wv.settings.javaScriptEnabled = true
            wv.settings.userAgentString = userAgent
            wv.loadUrl("about:blank")
            Log.d("bus", "warmUpWebView started")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    wv.stopLoading()
                    wv.destroy()
                    Log.d("bus", "warmUpWebView destroyed")
                } catch (e: Exception) {
                    Log.w("bus", "warmUp destroy err: ${e.message}")
                }
            }, 1000)
        } catch (e: Exception) {
            Log.w("bus", "warmUp fail: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("bus", "onPause — pauseTimers")
        if (this::webView.isInitialized) {
            webView.onPause()
            webView.pauseTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("bus", "onResume — resumeTimers")
        if (this::webView.isInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }

        val last = prefs.getLong("last_update_check", 0L)
        val thirtyDays = 30L * 24 * 60 * 60 * 1000
        val show = System.currentTimeMillis() - last > thirtyDays
        updateHint.visibility = if (show) View.VISIBLE else View.GONE
    }

    // === Прокси ===

    private fun setupWebViewNoProxy() {
        Log.d("bus", "PROXY_OVERRIDE supported: ${WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)}")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val proxyConfig = ProxyConfig.Builder()
                .addDirect()
                .build()
            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                Executors.newSingleThreadExecutor()
            ) {
                Log.d("bus", "PROXY_OVERRIDE applied (direct)")
            }
        } else {
            Log.w("bus", "PROXY_OVERRIDE NOT supported, using system proxy")
        }
    }

    // === Настройка WebView ===

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.settings.javaScriptEnabled = true
        wv.settings.userAgentString = userAgent
        wv.settings.blockNetworkImage = true
        wv.settings.loadsImagesAutomatically = false
        wv.settings.domStorageEnabled = true
        wv.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

        wv.webViewClient = object : WebViewClient() {

            private fun triggerParse(view: WebView?, url: String?, source: String) {
                Log.d("bus", "PARSE-TRIGGER ($source) url=$url mode=$mode isMain=${view == webView}")
                if (url == null) return
                if (view == webView) {
                    when (mode) {
                        "search" -> extractStopLinks(view)
                        "stop" -> parseSchedule(view)
                    }
                } else {
                    val w = workers?.firstOrNull { it.webView === view }
                    w?.parseWorker(view)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val blocked = url.contains("mc.yandex.ru") ||
                        url.contains("surveys.yandex.ru") ||
                        url.contains("static-mon.yandex.net") ||
                        url.contains("yandex.ru/metrika") ||
                        url.contains("yandex.ru/ads") ||
                        url.contains("an.yandex.ru") ||
                        url.contains("yabs.yandex.ru") ||
                        url.contains("matchid.adfox.yandex.ru") ||
                        url.contains("google-analytics.com") ||
                        url.contains("googletagmanager.com") ||
                        url.endsWith(".woff") || url.endsWith(".woff2")
                if (blocked) {
                    return WebResourceResponse(
                        "text/plain", "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d("bus", "START: url=$url")
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (url == "about:blank") return
                triggerParse(view, url, "commit")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("bus", "FIN: url=$url mode=$mode")
                if (url == "about:blank") return
                triggerParse(view, url, "finish")
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Log.e("bus", "ERR: ${request?.url} code=${error?.errorCode} desc=${error?.description}")
            }
        }
    }

    // === Воркеры ===

    private inner class FavWorker(val index: Int) {
        val webView: WebView = WebView(this@MainActivity)
        private val queue = ArrayDeque<Pair<String, String>>()
        private var currentId: String = ""
        private var busy = false

        init {
            setupWebView(webView)

            val baseClient = webView.webViewClient
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? = baseClient.shouldInterceptRequest(view, request)

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    baseClient.onPageStarted(view, url, favicon)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    if (url == "about:blank") {
                        Log.d("bus", "W$index blank ready, next")
                        processNext()
                    } else {
                        baseClient.onPageCommitVisible(view, url)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == "about:blank") return
                    baseClient.onPageFinished(view, url)
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
                ) {
                    baseClient.onReceivedError(view, request, error)
                }
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun onParsed(json: String) {
                    Log.d("bus", "W$index JS onParsed raw=$json")
                    runOnUiThread { handleWorkerResult(currentId, json) }
                }
            }, "AndroidW$index")
        }

        fun enqueue(id: String, url: String) {
            Log.d("bus", "W$index enqueue id=$id url=$url")
            queue.add(id to url)
            if (!busy) processNext()
        }

        private fun processNext() {
            val task = queue.removeFirstOrNull()
            if (task == null) {
                busy = false
                Log.d("bus", "W$index queue empty")
                return
            }
            busy = true
            currentId = task.first
            Log.d("bus", "W$index load: ${task.second} id=${task.first}")
            webView.loadUrl(task.second)
        }

        fun parseWorker(view: WebView?) {
            Log.d("bus", "W$index parse start id=$currentId url=${webView.url}")
            val js = """
                (function(){
                    if (window.__busParseStarted) return;
                    window.__busParseStarted = true;
                    var SEL = 'li.masstransit-vehicle-snippet-view';
                    var done = false;
                    function cleanup() {
                        if (window.__busObserver) { window.__busObserver.disconnect(); window.__busObserver = null; }
                    }
                    function grab() {
                        if (done) return;
                        var items = document.querySelectorAll(SEL);
                        if (items.length === 0) return;
                        done = true; cleanup();
                        var out = [];
                        for (var i = 0; i < items.length; i++) {
                            var t = (items[i].textContent || '').trim().replace(/\s+/g, ' ');
                            var m = t.match(/^(.+?)до\s*«([^»]+)»\s*(\d+)\s*мин/);
                            if (m) {
                                var route = m[1].trim();
                                if (route.length > 0 && route.length <= 5) out.push(route + '|||' + m[3] + '|||' + m[2].trim());
                            }
                        }
                        console.log('W$index PARSE out=' + JSON.stringify(out));
                        AndroidW$index.onParsed(JSON.stringify(out));
                    }
                    function start() {
                        if (!document.body) { setTimeout(start, 20); return; }
                        window.__busObserver = new MutationObserver(function(){ grab(); });
                        window.__busObserver.observe(document.body, {childList:true, subtree:true});
                        grab();
                        setTimeout(function(){
                            if (done) return;
                            done = true; cleanup();
                            console.log('W$index PARSE timeout');
                            AndroidW$index.onParsed('[]');
                        }, 6000);
                    }
                    start();
                })()
            """.trimIndent()
            view?.evaluateJavascript(js, null)
        }

        private fun handleWorkerResult(id: String, json: String) {
            Log.d("bus", "W$index result id=$id json=$json")
            try {
                val arr = JSONArray(json)
                val lines = mutableListOf<String>()
                val max = minOf(3, arr.length())
                for (i in 0 until max) {
                    val parts = arr.getString(i).split("|||")
                    if (parts.size < 3) continue
                    val route = parts[0]; val mins = parts[1]
                    lines.add("$route ➔ $mins мин")
                }
                val cache = if (lines.isEmpty()) "—" else lines.joinToString("\n")
                prefs.edit()
                    .putString("cache_$id", cache)
                    .putLong("updated_$id", System.currentTimeMillis())
                    .apply()
                renderFavorites()
            } catch (e: Exception) {
                Log.e("bus", "worker $index parsed err: ${e.message}")
            }
            webView.stopLoading()
            webView.loadUrl("about:blank")
            currentId = ""
        }
    }

    // === Избранное ===

    private fun getFavorites(): MutableList<String> {
        val s = prefs.getString("favorites", "") ?: ""
        if (s.isBlank()) return mutableListOf()
        return s.split(",").filter { it.isNotBlank() }.toMutableList()
    }

    private fun toggleFavorite() {
        if (currentStopId.isEmpty()) return
        val favs = getFavorites()
        val inFav = favs.contains(currentStopId)
        if (inFav) {
            favs.remove(currentStopId)
            Toast.makeText(this, "Удалено из избранного", Toast.LENGTH_SHORT).show()
        } else {
            favs.add(currentStopId)
            Toast.makeText(this, "Добавлено в избранное", Toast.LENGTH_SHORT).show()
        }
        prefs.edit()
            .putString("favorites", favs.joinToString(","))
            .putString("url_$currentStopId", currentStopUrl)
            .apply()
        updateFavButton()
        renderFavorites()
    }

    private fun renderFavorites() {
        favItems.clear()
        favItems.addAll(getFavorites())
        favAdapter.notifyDataSetChanged()
        refreshAll.visibility = if (favItems.isEmpty() || mode == "stop") View.GONE else View.VISIBLE
    }

    private fun startUpdateAll() {
        val ids = getFavorites()
        Log.d("bus", "startUpdateAll ids=$ids")
        if (ids.isEmpty()) return

        val needed = minOf(ids.size, 6)
        val current = workers?.toMutableList() ?: mutableListOf()
        if (current.size < needed) {
            for (i in current.size until needed) {
                current.add(FavWorker(i))
            }
            workers = current
            Log.d("bus", "workers extended to ${current.size}")
        }

        val w = workers!!
        for ((i, id) in ids.withIndex()) {
            val url = prefs.getString("url_$id", null) ?: continue
            w[i % w.size].enqueue(id, url)
        }
    }

    // === Экраны ===

    private fun showMainView() {
        Log.d("bus", "showMainView")
        mode = "search"
        parsed = false
        currentStopId = ""
        currentStopUrl = ""
        autoComplete.visibility = View.VISIBLE
        renameBar.visibility = View.GONE
        result.visibility = View.GONE
        favContainer.visibility = View.VISIBLE
        autoComplete.setText("")
        renderFavorites()
    }

    private fun showDetailView() {
        autoComplete.visibility = View.GONE
        renameBar.visibility = View.VISIBLE
        result.visibility = View.VISIBLE
        favContainer.visibility = View.GONE
        refreshAll.visibility = View.GONE
    }

    private fun updateFavButton() {
        val inFav = getFavorites().contains(currentStopId)
        favButton.text = if (inFav) "★ Убрать" else "★ В избранное"
    }

    // === Поиск ===

    private fun stopIdFromUrl(url: String): String? {
        val m = Regex("stopId\\]?=([^&]+)").find(url)
        return m?.groupValues?.get(1)
    }

    private fun searchStops(query: String) {
        Log.d("bus", "searchStops query=$query")
        if (query.isBlank()) return
        mode = "search"
        parsed = false
        autoComplete.visibility = View.VISIBLE
        renameBar.visibility = View.GONE
        result.visibility = View.GONE
        favContainer.visibility = View.VISIBLE
        suggestions.clear()
        suggestionUrls.clear()
        adapter.notifyDataSetChanged()

        val url = "https://yandex.ru/maps/213/moscow/?text=" +
                java.net.URLEncoder.encode("остановка $query", "UTF-8")
        Log.d("bus", "SEARCH load: $url")
        webView.loadUrl(url)
    }

    private fun extractStopLinks(view: WebView?) {
        Log.d("bus", "extractStopLinks start")
        val js = """
            (function(){
                if (window.__busExtractStarted) return;
                window.__busExtractStarted = true;
                var done = false;
                function cleanup() {
                    if (window.__busObserver) { window.__busObserver.disconnect(); window.__busObserver = null; }
                }
                function grab() {
                    if (done) return;
                    var all = document.querySelectorAll('a.link-overlay');
                    var raw = [];
                    var seen = {};
                    for (var i = 0; i < all.length; i++) {
                        var href = all[i].href || '';
                        if (href.indexOf('stopId') < 0) continue;
                        var m = href.match(/stopId\]?=([^&]+)/);
                        if (!m) continue;
                        var id = m[1];
                        if (seen[id]) continue;
                        seen[id] = 1;
                        var title = (all[i].textContent || '').trim().replace(/\s+/g, ' ');
                        var addr = '';
                        var el = all[i];
                        for (var k = 0; k < 5 && el; k++) {
                            var t = (el.textContent || '').trim().replace(/\s+/g, ' ');
                            if (t.indexOf('Москва,') >= 0) {
                                addr = t.slice(t.indexOf('Москва,'), t.indexOf('Москва,') + 100);
                                break;
                            }
                            el = el.parentElement;
                        }
                        raw.push({href: href, title: title, addr: addr});
                        if (raw.length >= 10) break;
                    }
                    if (raw.length === 0) return;
                    done = true; cleanup();
                    var counts = {};
                    for (var i = 0; i < raw.length; i++) counts[raw[i].title] = (counts[raw[i].title] || 0) + 1;
                    var idx = {};
                    var out = [];
                    for (var i = 0; i < raw.length; i++) {
                        var t = raw[i].title;
                        var label = t;
                        if (counts[t] > 1) { idx[t] = (idx[t] || 0) + 1; label = t + ' (' + idx[t] + ')'; }
                        out.push(raw[i].href + '|||' + label + '|||' + raw[i].addr);
                        if (out.length >= 5) break;
                    }
                    console.log('EXTRACT out=' + JSON.stringify(out));
                    Android.onSuggest(JSON.stringify(out));
                }
                function start() {
                    if (!document.body) { setTimeout(start, 20); return; }
                    window.__busObserver = new MutationObserver(function(){ grab(); });
                    window.__busObserver.observe(document.body, {childList:true, subtree:true});
                    grab();
                    setTimeout(function(){
                        if (done) return;
                        done = true; cleanup();
                        console.log('EXTRACT timeout');
                        Android.onSuggest('[]');
                    }, 7000);
                }
                start();
            })()
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun showSuggestions(json: String) {
        Log.d("bus", "showSuggestions raw=$json")
        try {
            val arr = JSONArray(json)
            suggestions.clear()
            suggestionUrls.clear()
            for (i in 0 until arr.length()) {
                val item = arr.getString(i)
                val parts = item.split("|||")
                if (parts.size < 3) continue
                val url = parts[0]
                val title = parts[1]
                val addr = parts[2]
                val id = stopIdFromUrl(url)
                val routes = if (id != null) prefs.getString("dir_$id", null) else null
                val dest = if (id != null) prefs.getString("dest_$id", null) else null
                val customName = if (id != null) prefs.getString("name_$id", null) else null
                val displayTitle = customName ?: title
                val subtitle = when {
                    routes != null && dest != null -> "$routes · до $dest"
                    routes != null -> routes
                    else -> addr
                }
                suggestions.add(displayTitle to subtitle)
                suggestionUrls.add(url)
            }
            adapter.notifyDataSetChanged()
            if (suggestions.isNotEmpty()) autoComplete.showDropDown()
        } catch (e: Exception) {
            Log.e("bus", "showSuggestions err: ${e.message}")
        }
    }

    private fun openStop(url: String?, title: String) {
        Log.d("bus", "openStop url=$url title=$title")
        if (url.isNullOrBlank()) return
        mode = "stop"
        parsed = false
        currentStopId = stopIdFromUrl(url) ?: ""
        currentStopUrl = url
        Log.d("bus", "OPEN id=$currentStopId")

        if (currentStopId.isNotEmpty() && prefs.getString("origName_$currentStopId", null) == null) {
            prefs.edit()
                .putString("origName_$currentStopId", title)
                .putString("url_$currentStopId", url)
                .apply()
        }

        val customName = prefs.getString("name_$currentStopId", null)
        val display = customName ?: title
        renameInput.setText(display)
        result.text = "Загрузка…"
        showDetailView()
        updateFavButton()
        Log.d("bus", "OPEN load: $url")
        webView.loadUrl(url)
    }

    private fun parseSchedule(view: WebView?) {
        Log.d("bus", "parseSchedule start url=${view?.url}")
        val js = """
            (function(){
                if (window.__busParseStarted) return;
                window.__busParseStarted = true;
                var SEL = 'li.masstransit-vehicle-snippet-view';
                var done = false;
                function cleanup() {
                    if (window.__busObserver) { window.__busObserver.disconnect(); window.__busObserver = null; }
                }
                function grab() {
                    if (done) return;
                    var items = document.querySelectorAll(SEL);
                    if (items.length === 0) return;
                    done = true; cleanup();
                    var out = [];
                    for (var i = 0; i < items.length; i++) {
                        var t = (items[i].textContent || '').trim().replace(/\s+/g, ' ');
                        var m = t.match(/^(.+?)до\s*«([^»]+)»\s*(\d+)\s*мин/);
                        if (m) {
                            var route = m[1].trim();
                            if (route.length > 0 && route.length <= 5) out.push(route + '|||' + m[3] + '|||' + m[2].trim());
                        }
                    }
                    console.log('PARSE out=' + JSON.stringify(out));
                    Android.onParsed(JSON.stringify(out));
                }
                function start() {
                    if (!document.body) { setTimeout(start, 20); return; }
                    window.__busObserver = new MutationObserver(function(){ grab(); });
                    window.__busObserver.observe(document.body, {childList:true, subtree:true});
                    grab();
                    setTimeout(function(){
                        if (done) return;
                        done = true; cleanup();
                        console.log('PARSE timeout');
                        Android.onParsed('[]');
                    }, 6000);
                }
                start();
            })()
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun resolveAttrColor(attrRes: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attrRes, tv, true)
        return tv.data
    }

    private fun isNightTheme(): Boolean {
        return (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun handleStopParsed(raw: String) {
        Log.d("bus", "handleStopParsed raw=$raw")
        try {
            val arr = JSONArray(raw)

            data class Row(val route: String, val mins: String, val dest: String)
            val rows = mutableListOf<Row>()
            var firstDest: String? = null
            val routes = mutableListOf<String>()

            for (i in 0 until arr.length()) {
                val item = arr.getString(i)
                val parts = item.split("|||")
                if (parts.size < 3) continue
                val route = parts[0]; val mins = parts[1]; val dest = parts[2]
                if (firstDest == null) firstDest = dest
                if (!routes.contains(route)) routes.add(route)
                rows.add(Row(route, mins, dest))
            }

            val routesStr = routes.joinToString(", ")
            if (routesStr.isNotEmpty() && firstDest != null && currentStopId.isNotEmpty()) {
                prefs.edit()
                    .putString("dir_$currentStopId", routesStr)
                    .putString("dest_$currentStopId", firstDest)
                    .apply()
            }

            if (rows.isEmpty()) {
                result.text = "Расписание не найдено"
                return
            }

            val sb = android.text.SpannableStringBuilder()
            val headerColor = resolveAttrColor(com.google.android.material.R.attr.colorPrimary)
            val green = android.graphics.Color.parseColor("#61B300")
            val black = if (isNightTheme()) android.graphics.Color.WHITE else android.graphics.Color.BLACK

            fun boldSpan(start: Int, end: Int) {
                sb.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            fun colorSpan(color: Int, start: Int, end: Int) {
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            val hs = sb.length
            sb.append("Автобусы: $routesStr\n\n")
            boldSpan(hs, sb.length)
            colorSpan(headerColor, hs, sb.length)

            for (row in rows) {
                val s1 = sb.length
                sb.append("${row.route} ➔ ${row.mins} мин")
                boldSpan(s1, sb.length)
                colorSpan(green, s1, sb.length)

                val s2 = sb.length
                sb.append(" (до ${row.dest})\n")
                boldSpan(s2, sb.length)
                colorSpan(black, s2, sb.length)
            }

            result.text = sb
        } catch (e: Exception) { Log.e("bus", "stop parsed err: ${e.message}") }
    }

    // === Адаптеры ===

    private class FavAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit,
        private val onDelete: (String) -> Unit,
        private val onOrderChanged: (List<String>) -> Unit
    ) : RecyclerView.Adapter<FavAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.favName)
            val bus1: TextView = v.findViewById(R.id.favBus1)
            val bus2: TextView = v.findViewById(R.id.favBus2)
            val bus3: TextView = v.findViewById(R.id.favBus3)
            val updated: TextView = v.findViewById(R.id.favUpdated)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_fav, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ctx = holder.itemView.context
            val id = items[position]
            val prefs = ctx.getSharedPreferences("stops", Context.MODE_PRIVATE)
            val name = prefs.getString("name_$id", null)
                ?: prefs.getString("origName_$id", null) ?: id
            val updated = prefs.getLong("updated_$id", 0L)
            val fresh = System.currentTimeMillis() - updated < 30_000
            val cache = prefs.getString("cache_$id", null)

            holder.name.text = name

            val lines = if (fresh && cache != null) cache.split("\n") else emptyList()
            holder.bus1.text = lines.getOrNull(0) ?: ""
            holder.bus2.text = lines.getOrNull(1) ?: ""
            holder.bus3.text = lines.getOrNull(2) ?: ""
            holder.updated.text = ""

            holder.itemView.setOnClickListener { onClick(id) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class SuggestAdapter(
        context: Context,
        private val items: MutableList<Pair<String, String>>
    ) : ArrayAdapter<Pair<String, String>>(context, 0, items) {
        private val noFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val r = FilterResults(); r.values = items.toList(); r.count = items.size; return r
            }
            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                clear()
                if (results?.values != null) addAll(results.values as List<Pair<String, String>>)
                notifyDataSetChanged()
            }
        }
        override fun getFilter(): Filter = noFilter
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)
            val t1 = v.findViewById<TextView>(android.R.id.text1)
            t1.text = items[position].first
            t1.setTypeface(null, android.graphics.Typeface.BOLD)
            return v
        }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            getView(position, convertView, parent)
    }
}