package com.example.bus

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.Collections

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

    private lateinit var favAdapter: FavAdapter
    private val favItems = mutableListOf<String>()

    private var parsed = false
    private var mode = "search"
    private var currentStopId: String = ""
    private var currentStopUrl: String = ""
    private var favUpdatingId: String = ""
    private var lastBackPress = 0L

    private val updateQueue = ArrayDeque<String>()

    private val prefs by lazy { getSharedPreferences("stops", MODE_PRIVATE) }

    private val suggestions = mutableListOf<Pair<String, String>>()
    private val suggestionUrls = mutableListOf<String>()
    private lateinit var adapter: SuggestAdapter

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        adapter = SuggestAdapter(this, suggestions)
        autoComplete.setAdapter(adapter)
        autoComplete.threshold = 1

        favAdapter = FavAdapter(favItems,
            onClick = { id ->
                val url = prefs.getString("url_$id", null)
                val name = prefs.getString("name_$id", null)
                    ?: prefs.getString("origName_$id", null) ?: id
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

        webView.settings.javaScriptEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        webView.settings.blockNetworkImage = true
        webView.settings.loadsImagesAutomatically = false
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val blocked = url.contains("mc.yandex.ru") ||
                        url.contains("surveys.yandex.ru") ||
                        url.contains("static-mon.yandex.net") ||
                        url.endsWith(".woff") || url.endsWith(".woff2")
                if (blocked) {
                    return WebResourceResponse(
                        "text/plain", "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return
                when (mode) {
                    "search" -> extractStopLinks(view)
                    "stop" -> parseSchedule(view)
                    "fav-update" -> parseSchedule(view)
                }
            }
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface fun onSuggest(json: String) { runOnUiThread { showSuggestions(json) } }
            @JavascriptInterface fun onParsed(json: String) { runOnUiThread { handleParsed(json) } }
            @JavascriptInterface fun onDebug(s: String) { Log.d("bus", "DBG:\n$s") }
        }, "Android")

        showMainView()
        startUpdateAll()
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
        refreshAll.visibility = if (favItems.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startUpdateAll() {
        val ids = getFavorites()
        if (ids.isEmpty()) return
        updateQueue.clear()
        updateQueue.addAll(ids)
        updateNextFavorite()
    }

    private fun updateNextFavorite() {
        if (updateQueue.isEmpty()) return
        val id = updateQueue.removeFirst()
        val url = prefs.getString("url_$id", null) ?: return updateNextFavorite()
        favUpdatingId = id
        mode = "fav-update"
        parsed = false
        webView.loadUrl(url)
    }

    // === Экраны ===

    private fun showMainView() {
        mode = "search"
        parsed = false
        currentStopId = ""
        currentStopUrl = ""
        autoComplete.visibility = View.VISIBLE
        renameBar.visibility = View.GONE
        result.visibility = View.GONE
        favList.visibility = View.VISIBLE
        autoComplete.setText("")
        renderFavorites()
    }

    private fun showDetailView() {
        autoComplete.visibility = View.GONE
        renameBar.visibility = View.VISIBLE
        result.visibility = View.VISIBLE
        favList.visibility = View.GONE
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
        if (query.isBlank()) return
        updateQueue.clear()
        mode = "search"
        parsed = false
        autoComplete.visibility = View.VISIBLE
        renameBar.visibility = View.GONE
        result.visibility = View.GONE
        favList.visibility = View.VISIBLE
        suggestions.clear()
        suggestionUrls.clear()
        adapter.notifyDataSetChanged()

        val url = "https://yandex.ru/maps/213/moscow/?text=" +
                java.net.URLEncoder.encode("остановка $query", "UTF-8")
        webView.loadUrl(url)
    }

    private fun extractStopLinks(view: WebView?) {
        val js = """
            (function(){
                var attempts = 0;
                var timer = setInterval(function(){
                    attempts++;
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
                    if (out.length > 0 || attempts > 40) {
                        clearInterval(timer);
                        Android.onSuggest(JSON.stringify(out));
                    }
                }, 500);
            })()
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun showSuggestions(json: String) {
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
        if (url.isNullOrBlank()) return
        mode = "stop"
        parsed = false
        currentStopId = stopIdFromUrl(url) ?: ""
        currentStopUrl = url

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
        webView.loadUrl(url)
    }

    private fun parseSchedule(view: WebView?) {
        val js = """
            (function(){
                if (window.__busParseStarted) return;
                window.__busParseStarted = true;
                var attempts = 0;
                var timer = setInterval(function(){
                    attempts++;
                    var items = document.querySelectorAll('li.masstransit-vehicle-snippet-view');
                    if (items.length > 0 || attempts > 40) {
                        clearInterval(timer);
                        var out = [];
                        for (var i = 0; i < items.length; i++) {
                            var t = (items[i].textContent || '').trim().replace(/\s+/g, ' ');
                            var m = t.match(/^(.+?)до\s*«([^»]+)»\s*(\d+)\s*мин/);
                            if (m) {
                                var route = m[1].trim();
                                if (route.length > 0 && route.length <= 5) out.push(route + '|||' + m[3] + '|||' + m[2].trim());
                            }
                        }
                        Android.onParsed(JSON.stringify(out));
                    }
                }, 400);
            })()
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun handleParsed(raw: String) {
        when (mode) {
            "fav-update" -> handleFavParsed(raw)
            "stop" -> handleStopParsed(raw)
        }
    }

    private fun handleStopParsed(raw: String) {
        try {
            val arr = JSONArray(raw)
            val sb = StringBuilder()
            var firstDest: String? = null
            val routes = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.getString(i)
                val parts = item.split("|||")
                if (parts.size < 3) continue
                val route = parts[0]; val mins = parts[1]; val dest = parts[2]
                if (firstDest == null) firstDest = dest
                if (!routes.contains(route)) routes.add(route)
                sb.append(route).append(" → ").append(mins).append(" мин (до ").append(dest).append(")\n")
            }
            val routesStr = routes.joinToString(", ")
            if (routesStr.isNotEmpty() && firstDest != null && currentStopId.isNotEmpty()) {
                prefs.edit()
                    .putString("dir_$currentStopId", routesStr)
                    .putString("dest_$currentStopId", firstDest)
                    .apply()
            }
            val header = if (routesStr.isNotEmpty() && firstDest != null)
                "Автобусы: $routesStr\nдо $firstDest\n\n" else ""
            result.text = if (sb.isEmpty()) "Расписание не найдено" else header + sb.toString()
        } catch (e: Exception) { Log.e("bus", "stop parsed err: ${e.message}") }
    }

    private fun handleFavParsed(raw: String) {
        try {
            val arr = JSONArray(raw)
            val lines = mutableListOf<String>()
            val max = minOf(3, arr.length())
            for (i in 0 until max) {
                val parts = arr.getString(i).split("|||")
                if (parts.size < 3) continue
                val route = parts[0]; val mins = parts[1]
                lines.add("$route → $mins мин")
            }
            if (favUpdatingId.isNotEmpty()) {
                val cache = if (lines.isEmpty()) "—" else lines.joinToString("\n")
                prefs.edit()
                    .putString("cache_$favUpdatingId", cache)
                    .putLong("updated_$favUpdatingId", System.currentTimeMillis())
                    .apply()
            }
            renderFavorites()
        } catch (e: Exception) { Log.e("bus", "fav parsed err: ${e.message}") }
        updateNextFavorite()
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
            val body: TextView = v.findViewById(R.id.favBody)
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
            val cache = prefs.getString("cache_$id", null)
                ?: "Нажмите, чтобы загрузить расписание"
            val updated = prefs.getLong("updated_$id", 0L)

            holder.name.text = name
            holder.body.text = cache
            holder.updated.text = if (updated > 0) {
                val mins = ((System.currentTimeMillis() - updated) / 60000).toInt()
                if (mins < 1) "обновлено сейчас" else "обновлено $mins мин назад"
            } else ""

            holder.itemView.setOnClickListener { onClick(id) }
        }

        override fun getItemCount() = items.size
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
            val v = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
            val t1 = v.findViewById<TextView>(android.R.id.text1)
            val t2 = v.findViewById<TextView>(android.R.id.text2)
            t1.text = items[position].first
            t2.text = items[position].second
            t2.textSize = 11f
            return v
        }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            getView(position, convertView, parent)
    }
}