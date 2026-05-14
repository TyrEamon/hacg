package io.github.yueeng.hacg

import android.animation.ObjectAnimator
import android.app.DownloadManager
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SearchRecentSuggestionsProvider
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Parcelable
import android.provider.SearchRecentSuggestions
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import io.github.yueeng.hacg.databinding.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private fun AppCompatActivity.setupSearchView(searchItem: MenuItem, initialQuery: String? = null, collapseOnSubmit: Boolean = false) {
    val search = searchItem.actionView as SearchView
    val manager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
    val info = manager.getSearchableInfo(ComponentName(this, ListActivity::class.java))
    search.setSearchableInfo(info)
    initialQuery?.let {
        searchItem.expandActionView()
        search.setQuery(it, false)
    }
    search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
        override fun onQueryTextSubmit(query: String?): Boolean {
            val key = query?.trim().orEmpty()
            if (key.isEmpty()) {
                toast(R.string.app_search_empty)
                return true
            }
            startActivity(Intent(Intent.ACTION_SEARCH).setClass(this@setupSearchView, ListActivity::class.java).putExtra(SearchManager.QUERY, key))
            if (collapseOnSubmit) {
                search.setQuery("", false)
                search.clearFocus()
                searchItem.collapseActionView()
            } else {
                search.clearFocus()
            }
            return true
        }

        override fun onQueryTextChange(newText: String?): Boolean = false
    })
}

class MainActivity : AppCompatActivity() {
    private var pendingUpdateDownloadId = -1L
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingUpdateDownloadId != -1L) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                toast(R.string.app_update_install_failed)
            } else {
                installDownloadedUpdate(pendingUpdateDownloadId)
            }
        }
    }
    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id == pendingUpdateDownloadId) installDownloadedUpdate(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater).apply {
            setSupportActionBar(toolbar)
            toolbarFavorites.setOnClickListener {
                startActivity(Intent(this@MainActivity, FavoriteActivity::class.java))
            }
            container.adapter = ArticleFragmentAdapter(this@MainActivity)
            TabLayoutMediator(tab, container) { tab, position -> tab.text = (container.adapter as ArticleFragmentAdapter).getPageTitle(position) }.attach()
        }
        setContentView(binding.root)
        if (savedInstanceState == null) checkVersion()
        var last = 0L
        addOnBackPressedCallback {
            if (System.currentTimeMillis() - last > 1500) {
                last = System.currentTimeMillis()
                toast(R.string.app_exit_confirm)
                return@addOnBackPressedCallback true
            }
            false
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(updateDownloadReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(updateDownloadReceiver)
        super.onStop()
    }

    private fun checkVersion(toast: Boolean = false) = lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.CREATED) {
            val release = HAcg.RELEASE_API.httpGetAwait()?.let {
                gson.fromJson(it.first, JGitHubRelease::class.java)
            }
            val ver = Version.from(release?.tagName)
            val apk = release?.assets?.firstOrNull { it.name == "app-release.apk" }
                ?: release?.assets?.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            val local = version()
            if (local != null && ver != null && local < ver) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.app_update_new, local, ver))
                    .setMessage(release?.body ?: "")
                    .setPositiveButton(R.string.app_update) { _, _ -> downloadUpdate(apk, ver) }
                    .setNeutralButton(R.string.app_publish) { _, _ -> openUri(HAcg.RELEASE) }
                    .setNegativeButton(R.string.app_cancel, null)
                    .create().show()
            } else {
                if (toast) Toast.makeText(this@MainActivity, getString(R.string.app_update_none, local), Toast.LENGTH_SHORT).show()
                checkConfig()
            }
        }
    }

    private fun downloadUpdate(asset: JGitHubReleaseAsset?, version: Version?) {
        val releaseAsset = asset ?: run {
            toast(R.string.app_update_download_missing)
            return
        }
        val url = releaseAsset.browserDownloadUrl
        if (url.isNullOrBlank()) {
            toast(R.string.app_update_download_missing)
            return
        }
        val fileName = "HAcg-${version ?: "update"}-${releaseAsset.name}"
            .replace("""[\\/:*?"<>|\s]+""".toRegex(), "_")
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(getString(R.string.app_update_download_title))
                .setDescription(fileName)
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            pendingUpdateDownloadId = manager.enqueue(request)
            toast(R.string.app_update_download_started)
        } catch (e: Exception) {
            toast(e.message ?: getString(R.string.app_update_download_failed))
        }
    }

    private fun installDownloadedUpdate(downloadId: Long) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: run {
            toast(R.string.app_update_download_failed)
            return
        }
        cursor.use {
            if (!it.moveToFirst()) return
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                if (status == DownloadManager.STATUS_FAILED) toast(R.string.app_update_download_failed)
                return
            }
        }
        val apkUri = manager.getUriForDownloadedFile(downloadId) ?: run {
            toast(R.string.app_update_download_failed)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            toast(R.string.app_update_install_permission)
            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            pendingUpdateDownloadId = -1L
        } catch (e: Exception) {
            toast(e.message ?: getString(R.string.app_update_install_failed))
        }
    }

    private fun checkConfig(toast: Boolean = false): Job = lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.CREATED) {
            HAcg.update(this@MainActivity, toast) {
                reload()
            }
        }
    }

    private fun reload() {
        ActivityMainBinding.bind(findViewById(R.id.coordinator)).container.adapter = ArticleFragmentAdapter(this)
    }

    class ArticleFragmentAdapter(fm: FragmentActivity) : FragmentStateAdapter(fm) {
        private val data = HAcg.categories.toList()

        fun getPageTitle(position: Int): CharSequence = data[position].second

        override fun getItemCount(): Int = data.size

        override fun createFragment(position: Int): Fragment =
            ArticleFragment().arguments(Bundle().string("url", data[position].first))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        setupSearchView(menu.findItem(R.id.search), collapseOnSubmit = true)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.search_clear -> true.also {
                val suggestions = SearchRecentSuggestions(this, SearchHistoryProvider.AUTHORITY, SearchHistoryProvider.MODE)
                suggestions.clearHistory()
            }

            R.id.config -> true.also {
                checkConfig(true)
            }

            R.id.settings -> true.also {
                HAcg.setHost(this) { reload() }
            }

            R.id.auto -> true.also {
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.CREATED) {
                        val good = withContext(Dispatchers.IO) { HAcg.hosts().pmap { u -> (u to u.test()) }.filter { it.second.first }.minByOrNull { it.second.second } }
                        if (good != null) {
                            HAcg.host = good.first
                            toast(getString(R.string.settings_config_auto_choose, good.first))
                            reload()
                        } else {
                            toast(R.string.settings_config_auto_failed)
                        }
                    }
                }
            }

            R.id.favorites -> true.also {
                startActivity(Intent(this, FavoriteActivity::class.java))
            }

            R.id.theme_mode -> true.also {
                val mode = getString(ThemeMode.cycle())
                toast(getString(R.string.app_theme_changed, mode))
                ThemeMode.applySaved()
            }

            R.id.user -> true.also {
                startActivity(Intent(this, WebActivity::class.java).apply {
                    if (user != 0) putExtra("url", "${HAcg.philosophy}/profile/$user")
                    else putExtra("login", true)
                })
            }

            R.id.philosophy -> true.also {
                startActivity(Intent(this, WebActivity::class.java))
            }

            R.id.about -> true.also {
                MaterialAlertDialogBuilder(this)
                    .setTitle("${getString(R.string.app_name)} ${version()}")
                    .setMessage(R.string.app_about_fork)
                    .setItems(arrayOf(getString(R.string.app_name))) { _, _ -> openUri(HAcg.wordpress) }
                    .setPositiveButton(R.string.app_publish) { _, _ -> openUri(HAcg.RELEASE) }
                    .setNeutralButton(R.string.app_update_check) { _, _ -> checkVersion(true) }
                    .setNegativeButton(R.string.app_cancel, null)
                    .create().show()
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}

class ListActivity : SwipeFinishActivity() {
    private var searchQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root) {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            val (url: String?, name: String?) = intent.let { i ->
                when {
                    i.hasExtra("url") -> (i.getStringExtra("url") to i.getStringExtra("name"))
                    i.hasExtra(SearchManager.QUERY) -> {
                        val key = i.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
                        if (key.isEmpty()) {
                            null to null
                        } else {
                            searchQuery = key
                            val suggestions = SearchRecentSuggestions(this, SearchHistoryProvider.AUTHORITY, SearchHistoryProvider.MODE)
                            suggestions.saveRecentQuery(key, null)
                            ("""${HAcg.wordpress}/?s=${Uri.encode(key)}&submit=%E6%90%9C%E7%B4%A2""" to getString(R.string.app_search_title, key))
                        }
                    }

                    else -> null to null
                }
            }
            if (url == null) {
                finish()
                return@setContentView
            }
            title = name
            val transaction = supportFragmentManager.beginTransaction()
            val fragment = supportFragmentManager.findFragmentById(R.id.container).takeIf { it is ArticleFragment }
                ?: ArticleFragment().arguments(Bundle().string("url", url))
            transaction.replace(R.id.container, fragment)
            transaction.commit()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_list, menu)
        setupSearchView(menu.findItem(R.id.search), searchQuery)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> true.also {
                onBackPressedDispatcher.onBackPressed()
            }

            R.id.search_clear -> true.also {
                val suggestions = SearchRecentSuggestions(this, SearchHistoryProvider.AUTHORITY, SearchHistoryProvider.MODE)
                suggestions.clearHistory()
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}

class FavoriteActivity : SwipeFinishActivity() {
    private val exportFavorites = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(Favorites.exportJson())
            }
            toast(R.string.favorite_exported)
        } catch (e: Exception) {
            toast(e.message ?: getString(R.string.favorite_export_failed))
        }
    }

    private val importFavorites = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            val count = Favorites.importJson(json ?: "")
            reload()
            toast(getString(R.string.favorite_imported, count))
        } catch (e: Exception) {
            toast(e.message ?: getString(R.string.favorite_import_failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root) {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.favorite_title)
            if (savedInstanceState == null) supportFragmentManager.beginTransaction()
                .replace(R.id.container, FavoriteFragment())
                .commit()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_favorites, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> true.also { onBackPressedDispatcher.onBackPressed() }
        R.id.favorite_export -> true.also { exportFavorites.launch("hacg-favorites.json") }
        R.id.favorite_import -> true.also { importFavorites.launch(arrayOf("application/json", "text/plain", "*/*")) }
        R.id.favorite_webdav_settings -> true.also { webDavSettings() }
        R.id.favorite_webdav_upload -> true.also { webDavUpload() }
        R.id.favorite_webdav_download -> true.also { webDavDownload() }
        else -> super.onOptionsItemSelected(item)
    }

    private fun reload() {
        (supportFragmentManager.findFragmentById(R.id.container) as? FavoriteFragment)?.reload()
    }

    private fun webDavSettings() {
        val config = FavoriteWebDav.config()
        val binding = FavoriteWebdavBinding.inflate(layoutInflater)
        binding.edit1.setText(config.url)
        binding.edit2.setText(config.username)
        binding.edit3.setText(config.password)
        binding.edit4.setText(config.path)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorite_webdav_settings)
            .setView(binding.root)
            .setPositiveButton(R.string.app_save) { _, _ ->
                FavoriteWebDav.save(
                    FavoriteWebDavConfig(
                        binding.edit1.text?.toString() ?: "",
                        binding.edit2.text?.toString() ?: "",
                        binding.edit3.text?.toString() ?: "",
                        binding.edit4.text?.toString() ?: ""
                    )
                )
                toast(R.string.app_save)
            }
            .setNegativeButton(R.string.app_cancel, null)
            .create().show()
    }

    private fun webDavConfigOrEdit(): FavoriteWebDavConfig? {
        val config = FavoriteWebDav.config()
        if (config.ready) return config
        toast(R.string.favorite_webdav_required)
        webDavSettings()
        return null
    }

    private fun webDavUpload() {
        val config = webDavConfigOrEdit() ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    FavoriteWebDav.upload(config, Favorites.exportJson())
                }
                toast(R.string.favorite_webdav_uploaded)
            } catch (e: Exception) {
                toast(e.message ?: getString(R.string.favorite_webdav_failed))
            }
        }
    }

    private fun webDavDownload() {
        val config = webDavConfigOrEdit() ?: return
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    Favorites.importJson(FavoriteWebDav.download(config))
                }
                reload()
                toast(getString(R.string.favorite_imported, count))
            } catch (e: Exception) {
                toast(e.message ?: getString(R.string.favorite_webdav_failed))
            }
        }
    }
}

class FavoriteFragment : Fragment() {
    private val adapter by lazy { ArticleFragment.ArticleAdapter() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentListBinding.inflate(inflater, container, false).apply {
            recycler.setHasFixedSize(true)
            recycler.adapter = adapter
            image1.setOnClickListener { reload() }
            swipe.setOnRefreshListener { reload() }
            reload()
        }.root

    override fun onResume() {
        super.onResume()
        reload()
    }

    fun reload() {
        val view = view ?: return
        val binding = FragmentListBinding.bind(view)
        val items = Favorites.all()
        adapter.replaceAll(items)
        binding.image1.visibility = if (items.isEmpty()) View.VISIBLE else View.INVISIBLE
        binding.swipe.isRefreshing = false
    }
}

class SearchHistoryProvider : SearchRecentSuggestionsProvider() {
    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.SuggestionProvider"
        const val MODE: Int = DATABASE_MODE_QUERIES
    }

    init {
        setupSuggestions(AUTHORITY, MODE)
    }
}

class ArticlePagingSource(private val title: (String) -> Unit) : PagingSource<String, Article>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, Article> = try {
        val dom = params.key!!.httpGetAwait()!!.jsoup()
        listOf("h1.page-title>span", "h1#site-title", "title").asSequence().map { dom.select(it).text() }
            .firstOrNull { it.isNotEmpty() }?.let(title::invoke)
        val articles = dom.select("article").map { o -> Article(o) }.toList()
        val next = dom.select("a.nextpostslink").lastOrNull()?.takeIf { "»" == it.text() }?.attr("abs:href")
            ?: dom.select("#wp_page_numbers a").lastOrNull()?.takeIf { ">" == it.text() }?.attr("abs:href")
            ?: dom.select("#nav-below .nav-previous a").firstOrNull()?.attr("abs:href")
        LoadResult.Page(articles, null, next)
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<String, Article>): String? = null
}

class ArticleViewModel(private val handle: SavedStateHandle, args: Bundle?) : ViewModel() {
    var retry: Boolean
        get() = handle["retry"] ?: false
        set(value) = handle.set("retry", value)
    val title = handle.getLiveData<String>("title")
    val source = Paging(handle, args?.getString("url")) { ArticlePagingSource { title.postValue(it) } }
    val data = handle.getLiveData<List<Article>>("data")
    val last = handle.getLiveData("last", -1)
}

class ArticleViewModelFactory(owner: SavedStateRegistryOwner, private val args: Bundle? = null) : AbstractSavedStateViewModelFactory(owner, args) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T = ArticleViewModel(handle, args) as T
}

class ArticleFragment : Fragment() {
    private val viewModel: ArticleViewModel by viewModels { ArticleViewModelFactory(this, bundleOf("url" to defurl)) }
    private val adapter by lazy { ArticleAdapter() }

    private val defurl: String
        get() = requireArguments().getString("url")!!.let { uri -> if (uri.startsWith("/")) "${HAcg.web}$uri" else uri }

    private val isSearch: Boolean
        get() = Uri.parse(defurl).getQueryParameter("s") != null

    private suspend fun Article.withDetailCover(): Article? {
        val html = link?.takeIf { it.isNotBlank() }?.httpGetAwait() ?: return null
        val image = html.jsoup().select(".entry-content img").asSequence()
            .filterNot { it.hasClass("avatar") }
            .map { it.attr("abs:src").trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
            ?: return null
        return copy(image = image)
    }

    private fun query(refresh: Boolean = false) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                if (refresh) adapter.clear()
                val (list, _) = viewModel.source.query(refresh)
                if (list != null) adapter.addAll(list)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.last.value = adapter.last
        viewModel.data.value = adapter.data
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.last.value?.let { adapter.last = it }
        viewModel.data.value?.let { adapter.addAll(it) }
        if (isSearch) adapter.coverResolver = { article, update ->
            lifecycleScope.launch {
                val updated = withContext(Dispatchers.IO) { article.withDetailCover() }
                if (updated != null) update(updated)
            }
        }
        if (adapter.itemCount == 0) query()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentListBinding.inflate(inflater, container, false).apply {
            viewModel.source.state.observe(viewLifecycleOwner) {
                adapter.state.postValue(it)
                swipe.isRefreshing = it is LoadState.Loading
                image1.visibility = if (it is LoadState.Error && adapter.itemCount == 0) View.VISIBLE else View.INVISIBLE
                if (it is LoadState.Error && adapter.itemCount == 0) if (viewModel.retry) activity?.openOptionsMenu() else activity?.toast(R.string.app_network_retry)
            }
            if (requireActivity().title.isNullOrEmpty()) {
                requireActivity().title = getString(R.string.app_name)
                viewModel.title.observe(viewLifecycleOwner) {
                    requireActivity().title = it
                }
            }
            image1.setOnClickListener {
                viewModel.retry = true
                query(true)
            }
            swipe.setOnRefreshListener { query(true) }
            recycler.setHasFixedSize(true)
            recycler.adapter = adapter.withLoadStateFooter(FooterAdapter({ adapter.itemCount }) {
                query()
            })
            recycler.loading {
                when (viewModel.source.state.value) {
                    LoadState.NotLoading(false) -> query()
                }
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    adapter.refreshFlow.collectLatest {
                        recycler.scrollToPosition(0)
                    }
                }
            }
        }.root

    class ArticleHolder(private val binding: ArticleItemBinding) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {
        private val previewTagChars = 75
        private val datafmt = SimpleDateFormat("yyyy-MM-dd hh:ss", Locale.getDefault())
        private val context = binding.root.context
        private var tagsExpanded = false
        var article: Article? = null
            set(value) {
                val item = value!!
                if (field?.id != item.id || field?.link != item.link) tagsExpanded = false
                field = value
                binding.text1.text = item.title
                binding.text1.visibility = if (item.title.isNotEmpty()) View.VISIBLE else View.GONE
                val color = randomColor()
                binding.text1.setTextColor(color)
                binding.text2.text = item.content
                binding.text2.visibility = if (item.content?.isNotEmpty() == true) View.VISIBLE else View.GONE
                bindTags(item)
                binding.text4.text = context.getString(R.string.app_list_time, datafmt.format(item.time ?: Date()), item.author?.name ?: "", item.comments)
                binding.text4.setTextColor(color)
                binding.text4.visibility = if (binding.text4.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                Glide.with(context).load(item.img).placeholder(R.drawable.loading).error(R.drawable.placeholder).into(binding.image1)
            }

        init {
            binding.root.setOnClickListener(this)
            binding.root.tag = this
            binding.text3.movementMethod = LinkMovementMethod.getInstance()
        }

        override fun onClick(p0: View?) {
            context.startActivity(Intent(context, InfoActivity::class.java).putExtra("article", article as Parcelable))
        }

        private fun bindTags(item: Article) {
            val tags = if (tagsExpanded) item.expend else item.previewTags()
            val span = tags.spannable(
                string = { it.name },
                call = { tag ->
                    if (tag.url.isBlank()) {
                        tagsExpanded = true
                        bindTags(item)
                    } else {
                        context.startActivity(Intent(context, ListActivity::class.java).putExtra("url", tag.url).putExtra("name", tag.name))
                    }
                },
                clickable = { it.url.isNotBlank() || it.name.startsWith("+") }
            )
            binding.text3.text = span
            binding.text3.visibility = if (tags.isNotEmpty()) View.VISIBLE else View.GONE
        }

        private fun Article.previewTags(): List<Tag> {
            val all = expend
            val tags = mutableListOf<Tag>()
            var chars = 0
            for (tag in all) {
                val next = chars + tag.name.length + if (tags.isEmpty()) 0 else 1
                tags += tag
                chars = next
                if (chars >= previewTagChars) break
            }
            val hidden = all.size - tags.size
            return if (hidden > 0) tags + Tag("+$hidden", "") else tags
        }
    }

    class ArticleDiffCallback : DiffUtil.ItemCallback<Article>() {
        override fun areItemsTheSame(oldItem: Article, newItem: Article): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Article, newItem: Article): Boolean = oldItem == newItem
    }

    class ArticleAdapter : PagingAdapter<Article, ArticleHolder>(ArticleDiffCallback()) {
        var last: Int = -1
        var coverResolver: (((Article, (Article) -> Unit) -> Unit))? = null
        private val coverRequests = mutableSetOf<String>()
        private val interpolator = DecelerateInterpolator(3F)
        private val from: Float by lazy {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200F, HAcgApplication.instance.resources.displayMetrics)
        }

        override fun onBindViewHolder(holder: ArticleHolder, position: Int) {
            val item = data[position]
            holder.article = item
            maybeResolveCover(item)
            if (position > last) {
                last = position
                ObjectAnimator.ofFloat(holder.itemView, View.TRANSLATION_Y.name, from, 0F)
                    .setDuration(1000).also { it.interpolator = interpolator }.start()
            }
        }

        override fun clear(): DataAdapter<Article, ArticleHolder> = super.clear().apply {
            last = -1
            coverRequests.clear()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleHolder =
            ArticleHolder(ArticleItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        private fun maybeResolveCover(article: Article) {
            val resolver = coverResolver ?: return
            if (!article.image.isNullOrBlank()) return
            val key = article.coverKey() ?: return
            if (!coverRequests.add(key)) return
            resolver(article) { updated ->
                val items = data.toMutableList()
                val index = items.indexOfFirst { it.coverKey() == key }
                if (index < 0 || !items[index].image.isNullOrBlank()) return@resolver
                items[index] = updated
                replaceAll(items)
            }
        }

        private fun Article.coverKey(): String? = id.takeIf { it > 0 }?.let { "id:$it" }
            ?: link?.takeIf { it.isNotBlank() }?.let { "url:$it" }
    }
}

class MsgHolder(private val binding: ListMsgItemBinding, retry: () -> Unit) : RecyclerView.ViewHolder(binding.root) {
    init {
        binding.root.setOnClickListener { if (state is LoadState.Error) retry() }
    }

    private var state: LoadState? = null
    fun bind(value: LoadState, empty: () -> Boolean) {
        state = value
        binding.text1.setText(
            when (value) {
                is LoadState.NotLoading -> when {
                    value.endOfPaginationReached && empty() -> R.string.app_list_empty
                    value.endOfPaginationReached -> R.string.app_list_complete
                    else -> R.string.app_list_loadmore
                }

                is LoadState.Error -> R.string.app_list_failed
                else -> R.string.app_list_loading
            }
        )
    }
}

class FooterAdapter(private val count: () -> Int, private val retry: () -> Unit) : LoadStateAdapter<MsgHolder>() {
    override fun displayLoadStateAsItem(loadState: LoadState): Boolean = when (loadState) {
        is LoadState.NotLoading -> count() != 0 || loadState.endOfPaginationReached
        is LoadState.Loading -> count() != 0
        else -> true
    }

    override fun onBindViewHolder(holder: MsgHolder, loadState: LoadState) {
        holder.bind(loadState) { count() == 0 }
    }

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): MsgHolder =
        MsgHolder(ListMsgItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)) { retry() }
}
