package com.radiodroid.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Toast
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.radiodroid.app.BuildConfig
import com.radiodroid.app.databinding.ActivityChirpRepeaterbookSearchBinding
import com.radiodroid.app.radio.ChirpCsvExporter
import com.radiodroid.app.radio.repeaterbook.RepeaterBookApiException
import com.radiodroid.app.radio.repeaterbook.RepeaterBookGmrsProx
import com.radiodroid.app.radio.repeaterbook.RepeaterBookHttp
import com.radiodroid.app.radio.repeaterbook.RepeaterBookProx2
import com.radiodroid.app.radio.repeaterbook.RepeaterBookJsonParser
import com.radiodroid.app.radio.repeaterbook.RepeaterBookJsonRow
import com.radiodroid.app.radio.repeaterbook.RepeaterBookResultsAdapter
import com.radiodroid.app.radio.repeaterbook.RepeaterBookToChannelMapper
import com.radiodroid.app.radio.repeaterbook.chirp.ChirpRepeaterBookApiQuery
import com.radiodroid.app.radio.repeaterbook.chirp.ChirpRbClientParams
import com.radiodroid.app.radio.repeaterbook.chirp.ChirpRepeaterBookData
import com.radiodroid.app.radio.repeaterbook.chirp.ChirpRepeaterBookFilters
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CHIRP-style country/state/service picker, but data comes from the official RepeaterBook API
 * (`export.php` / `exportROW.php` at `https://www.repeaterbook.com/api`), then client-side filters
 * similar to CHIRP’s [do_fetch](https://github.com/kk7ds/chirp/blob/master/chirp/sources/repeaterbook.py).
 *
 * Uses [RepeaterBookHttp] (same User-Agent and token as [RepeaterBookSearchActivity]): JSON export for
 * most queries; **US GMRS + lat/lon + distance** uses [RepeaterBookGmrsProx] (HTML proximity page).
 */
class ChirpRepeaterBookSearchActivity : AppCompatActivity() {

    private companion object {
        const val DEFAULT_LOCATION_DISTANCE_MI = 30.0
    }

    private lateinit var binding: ActivityChirpRepeaterbookSearchBinding

    private val allRows = mutableListOf<RepeaterBookJsonRow>()
    private val visibleRows = mutableListOf<RepeaterBookJsonRow>()

    private val adapter = RepeaterBookResultsAdapter(
        visibleRows,
        onToggle = { row, checked ->
            row.selected = checked
            updateImportButton()
        },
    )

    private var countryAdapter: ArrayAdapter<String>? = null
    private var stateAdapter: ArrayAdapter<String>? = null

    private val prox2FeatureIds = mutableSetOf<String>()
    private var prox2StatusId: String = RepeaterBookProx2.STATUS_ANY
    private var prox2IncludeSimplex: Boolean = false
    private var prox2FilterChipsInitialized: Boolean = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) {
            fetchAndFillLocation()
        } else {
            Toast.makeText(this, R.string.chirp_rb_location_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (EepromHolder.eeprom == null) {
            Toast.makeText(this, R.string.repeaterbook_need_eeprom, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        binding = ActivityChirpRepeaterbookSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.textAttribution.text = HtmlCompat.fromHtml(
            getString(R.string.chirp_rb_attribution_html),
            HtmlCompat.FROM_HTML_MODE_LEGACY,
        )
        binding.textAttribution.movementMethod = LinkMovementMethod.getInstance()

        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter
        binding.recyclerResults.isNestedScrollingEnabled = false

        binding.btnSelectAllVisible.setOnClickListener { selectAllVisible() }

        countryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ChirpRepeaterBookData.allCountries,
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerCountry.adapter = it
        }

        val usIndex = ChirpRepeaterBookData.allCountries.indexOf("United States")
        if (usIndex >= 0) binding.spinnerCountry.setSelection(usIndex)

        binding.spinnerCountry.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                onCountryChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        onCountryChanged()

        binding.radioGroupService.setOnCheckedChangeListener { _, _ ->
            updateServiceEnabled()
            updateAmateurProx2Visibility()
        }

        ensureProx2FilterChips()
        binding.btnProx2Advanced.setOnClickListener { showProx2AdvancedDialog() }

        binding.btnSearch.setOnClickListener { runSearch() }
        binding.btnUseMyLocation.setOnClickListener { onUseMyLocationClicked() }
        binding.btnExit.setOnClickListener { finish() }
        binding.btnImport.setOnClickListener { importSelected() }

        binding.editFilter.doAfterTextChanged { applyQuickFilter() }

        if (!hasRepeaterBookConfig()) {
            Toast.makeText(this, R.string.repeaterbook_configure_token, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_repeaterbook_search, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.repeaterbook_menu_select_all -> {
                selectAllVisible()
                return true
            }
            R.id.repeaterbook_menu_clear -> {
                allRows.forEach { it.selected = false }
                adapter.notifyDataSetChanged()
                updateImportButton()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun onUseMyLocationClicked() {
        if (!hasAnyLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }
        fetchAndFillLocation()
    }

    private fun hasAnyLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun fetchAndFillLocation() {
        binding.btnUseMyLocation.isEnabled = false
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) { readBestLocationLatLon() }
            binding.btnUseMyLocation.isEnabled = true
            if (pair != null) {
                binding.editLat.setText(String.format(Locale.US, "%.6f", pair.first))
                binding.editLon.setText(String.format(Locale.US, "%.6f", pair.second))
                val currentDistance = binding.editDistanceMiles.text?.toString()?.toDoubleOrNull() ?: 0.0
                if (currentDistance <= 0.0) {
                    binding.editDistanceMiles.setText(
                        String.format(Locale.US, "%.0f", DEFAULT_LOCATION_DISTANCE_MI),
                    )
                }
            } else {
                Toast.makeText(
                    this@ChirpRepeaterBookSearchActivity,
                    R.string.chirp_rb_location_unavailable,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Last known location if fresh enough; otherwise [LocationManager.getCurrentLocation] on API 30+.
     */
    @SuppressLint("MissingPermission")
    private suspend fun readBestLocationLatLon(): Pair<Double, Double>? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        fun lastCandidates(): List<Location> = listOfNotNull(
            runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
        )

        val lastBest = lastCandidates().maxByOrNull { it.time }
        val staleMs = 5 * 60_000L
        if (lastBest != null && System.currentTimeMillis() - lastBest.time < staleMs) {
            return Pair(lastBest.latitude, lastBest.longitude)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                val fresh = withTimeoutOrNull(20_000) {
                    suspendCancellableCoroutine { cont ->
                        val cancel = CancellationSignal()
                        val exec = Executors.newSingleThreadExecutor()
                        cont.invokeOnCancellation {
                            cancel.cancel()
                            exec.shutdown()
                        }
                        lm.getCurrentLocation(provider, cancel, exec) { loc ->
                            exec.shutdown()
                            if (cont.isActive) {
                                cont.resume(loc)
                            }
                        }
                    }
                }
                if (fresh != null) {
                    return Pair(fresh.latitude, fresh.longitude)
                }
            }
        }

        return lastBest?.let { Pair(it.latitude, it.longitude) }
    }

    private fun hasRepeaterBookConfig(): Boolean =
        BuildConfig.REPEATERBOOK_APP_TOKEN.isNotBlank() &&
            BuildConfig.REPEATERBOOK_CONTACT_EMAIL.isNotBlank()

    private fun selectedCountry(): String =
        binding.spinnerCountry.selectedItem?.toString().orEmpty()

    private fun updateServiceEnabled() {
        val us = selectedCountry() == "United States"
        binding.radioServiceAmateur.isEnabled = true
        binding.radioServiceGmrs.isEnabled = us
        if (!us && binding.radioServiceGmrs.isChecked) {
            binding.radioServiceAmateur.isChecked = true
        }
    }

    private fun onCountryChanged() {
        updateServiceEnabled()
        val country = selectedCountry()
        val states = ChirpRepeaterBookData.statesForCountry(country)
        stateAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            states,
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerState.adapter = it
        }
        binding.spinnerState.setSelection(0)
        updateAmateurProx2Visibility()
    }

    private fun serviceGmrs(): Boolean =
        binding.radioServiceGmrs.isChecked

    private fun updateAmateurProx2Visibility() {
        val show = !serviceGmrs() && selectedCountry() == "United States"
        binding.layoutAmateurProx2.isVisible = show
    }

    private fun ensureProx2FilterChips() {
        if (prox2FilterChipsInitialized) return
        prox2FilterChipsInitialized = true
        fun filterChip(label: String, value: String, checked: Boolean) =
            Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = label
                tag = value
                isCheckable = true
                isChecked = checked
            }
        val bands = listOf(
            getString(R.string.chirp_rb_band_10m) to RepeaterBookProx2.BAND_10M,
            getString(R.string.chirp_rb_band_6m) to RepeaterBookProx2.BAND_6M,
            getString(R.string.chirp_rb_band_2m) to RepeaterBookProx2.BAND_2M,
            getString(R.string.chirp_rb_band_125m) to RepeaterBookProx2.BAND_125M,
            getString(R.string.chirp_rb_band_70cm) to RepeaterBookProx2.BAND_70CM,
            getString(R.string.chirp_rb_band_33cm) to RepeaterBookProx2.BAND_33CM,
            getString(R.string.chirp_rb_band_23cm) to RepeaterBookProx2.BAND_23CM,
        )
        for ((label, value) in bands) {
            binding.chipGroupProx2Bands.addView(
                filterChip(label, value, checked = value == RepeaterBookProx2.BAND_2M),
            )
        }
        val modes = listOf(
            getString(R.string.chirp_rb_mode_fm) to RepeaterBookProx2.MODE_FM,
            getString(R.string.chirp_rb_mode_dmr) to RepeaterBookProx2.MODE_DMR,
            getString(R.string.chirp_rb_mode_dstar) to RepeaterBookProx2.MODE_DSTAR,
            getString(R.string.chirp_rb_mode_m17) to RepeaterBookProx2.MODE_M17,
            getString(R.string.chirp_rb_mode_nxdn) to RepeaterBookProx2.MODE_NXDN,
            getString(R.string.chirp_rb_mode_p25) to RepeaterBookProx2.MODE_P25,
            getString(R.string.chirp_rb_mode_fusion) to RepeaterBookProx2.MODE_FUSION,
        )
        for ((label, value) in modes) {
            binding.chipGroupProx2Modes.addView(
                filterChip(label, value, checked = value == RepeaterBookProx2.MODE_FM),
            )
        }
    }

    private fun collectCheckedChipValues(group: ChipGroup): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) out.add(chip.tag as String)
        }
        return out
    }

    private fun showProx2AdvancedDialog() {
        data class Feat(val id: String, val labelRes: Int)

        val feats = listOf(
            Feat(RepeaterBookProx2.FEATURE_ALLSTAR, R.string.feat_allstar),
            Feat(RepeaterBookProx2.FEATURE_AUTOPATCH, R.string.feat_autopatch),
            Feat(RepeaterBookProx2.FEATURE_EPOWER, R.string.feat_epower),
            Feat(RepeaterBookProx2.FEATURE_ECHOLINK, R.string.feat_echolink),
            Feat(RepeaterBookProx2.FEATURE_IRLP, R.string.feat_irlp),
            Feat(RepeaterBookProx2.FEATURE_WIRES_X, R.string.feat_wiresx),
            Feat(RepeaterBookProx2.FEATURE_WIDE_AREA, R.string.feat_wide_area),
            Feat(RepeaterBookProx2.FEATURE_WX, R.string.feat_wx),
        )
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 12f).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        container.addView(
            TextView(this).apply {
                text = getString(R.string.chirp_rb_prox2_features_help)
            },
        )
        val featChecks = mutableMapOf<String, CheckBox>()
        for (f in feats) {
            val cb = CheckBox(this).apply {
                text = getString(f.labelRes)
                isChecked = prox2FeatureIds.contains(f.id)
            }
            featChecks[f.id] = cb
            container.addView(cb)
        }
        container.addView(
            TextView(this).apply {
                text = getString(R.string.chirp_rb_prox2_status)
                val top = (resources.displayMetrics.density * 16f).toInt()
                setPadding(0, top, 0, top / 2)
            },
        )
        val statusGroup = RadioGroup(this)
        val rbAllId = View.generateViewId()
        val rbOnAirId = View.generateViewId()
        statusGroup.addView(
            RadioButton(this).apply {
                id = rbAllId
                text = getString(R.string.chirp_rb_prox2_status_all)
            },
        )
        statusGroup.addView(
            RadioButton(this).apply {
                id = rbOnAirId
                text = getString(R.string.chirp_rb_prox2_status_on_air)
            },
        )
        when (prox2StatusId) {
            RepeaterBookProx2.STATUS_ON_AIR_CONFIRMED -> statusGroup.check(rbOnAirId)
            else -> statusGroup.check(rbAllId)
        }
        container.addView(statusGroup)
        val cbSimplex = CheckBox(this).apply {
            text = getString(R.string.chirp_rb_prox2_include_simplex)
            isChecked = prox2IncludeSimplex
            val top = (resources.displayMetrics.density * 12f).toInt()
            setPadding(0, top, 0, 0)
        }
        container.addView(cbSimplex)
        scroll.addView(container)
        AlertDialog.Builder(this)
            .setTitle(R.string.chirp_rb_prox2_advanced_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                prox2FeatureIds.clear()
                for (f in feats) {
                    if (featChecks[f.id]?.isChecked == true) {
                        prox2FeatureIds.add(f.id)
                    }
                }
                prox2StatusId = if (statusGroup.checkedRadioButtonId == rbOnAirId) {
                    RepeaterBookProx2.STATUS_ON_AIR_CONFIRMED
                } else {
                    RepeaterBookProx2.STATUS_ANY
                }
                prox2IncludeSimplex = cbSimplex.isChecked
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runSearch() {
        if (!hasRepeaterBookConfig()) {
            Toast.makeText(this, R.string.repeaterbook_configure_token, Toast.LENGTH_LONG).show()
            return
        }
        val country = selectedCountry()
        if (country.isBlank()) {
            Toast.makeText(this, R.string.chirp_rb_pick_country, Toast.LENGTH_SHORT).show()
            return
        }
        val stateItem = binding.spinnerState.selectedItem?.toString()
            ?: ChirpRepeaterBookData.stateAll

        val lat = binding.editLat.text?.toString()?.toDoubleOrNull() ?: 0.0
        val lon = binding.editLon.text?.toString()?.toDoubleOrNull() ?: 0.0
        val distMiles = binding.editDistanceMiles.text?.toString()?.toDoubleOrNull() ?: 0.0
        val gmrsSelected = serviceGmrs()
        val useHtmlProx = country == "United States" &&
            lat != 0.0 &&
            lon != 0.0 &&
            distMiles > 0.0
        val useAmateurProx2 = !gmrsSelected && useHtmlProx
        if (useAmateurProx2) {
            val proxBands = collectCheckedChipValues(binding.chipGroupProx2Bands)
            val proxFreq = binding.editProx2Freq.text?.toString()?.trim().orEmpty()
            if (proxBands.isEmpty() && proxFreq.isEmpty()) {
                Toast.makeText(
                    this,
                    R.string.chirp_rb_prox2_need_band_or_freq,
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            val proxModes = collectCheckedChipValues(binding.chipGroupProx2Modes)
            if (proxModes.isEmpty()) {
                Toast.makeText(this, R.string.chirp_rb_prox2_need_mode, Toast.LENGTH_LONG).show()
                return
            }
        }

        val useGmrsProx = gmrsSelected && useHtmlProx
        val usingProximity = useGmrsProx || useAmateurProx2
        if (!usingProximity &&
            country in ChirpRepeaterBookData.naCountries &&
            stateItem.equals(ChirpRepeaterBookData.stateAll, ignoreCase = true)
        ) {
            Toast.makeText(this, R.string.chirp_rb_need_state_or_proximity, Toast.LENGTH_LONG).show()
            return
        }

        binding.btnSearch.isEnabled = false
        binding.progressSearch.visibility = View.VISIBLE

        val chirpFilter = binding.editFilterChirp.text?.toString().orEmpty()
        val openOnly = binding.checkOpenOnly.isChecked
        val proxBandsSnap = if (useAmateurProx2) {
            collectCheckedChipValues(binding.chipGroupProx2Bands)
        } else {
            emptyList()
        }
        val proxModesSnap = if (useAmateurProx2) {
            collectCheckedChipValues(binding.chipGroupProx2Modes)
        } else {
            emptyList()
        }
        val proxFreqSnap = if (useAmateurProx2) {
            binding.editProx2Freq.text?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val proxFeaturesSnap = prox2FeatureIds.toList()
        val proxStatusSnap = prox2StatusId
        val proxSimplexSnap = prox2IncludeSimplex

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val objs = when {
                        useGmrsProx -> {
                            RepeaterBookGmrsProx.fetchRepeaters(
                                RepeaterBookHttp.httpClient(),
                                latDeg = lat,
                                longDeg = lon,
                                distance = distMiles,
                                miles = true,
                            )
                        }
                        useAmateurProx2 -> {
                            RepeaterBookProx2.fetchRepeaters(
                                RepeaterBookHttp.httpClient(),
                                latDeg = lat,
                                longDeg = lon,
                                distance = distMiles,
                                miles = true,
                                bandIds = proxBandsSnap,
                                modeIds = proxModesSnap,
                                freqMhz = proxFreqSnap,
                                featureIds = proxFeaturesSnap,
                                statusId = proxStatusSnap,
                                includeSimplex = proxSimplexSnap,
                            )
                        }
                        else -> {
                            val query = ChirpRepeaterBookApiQuery.toRepeaterBookQuery(
                                country = country,
                                stateUi = stateItem,
                                serviceGmrs = gmrsSelected,
                            )
                            val json = RepeaterBookHttp.fetchRepeaters(query)
                            RepeaterBookJsonParser.parseResults(json)
                        }
                    }

                    val params = if (useGmrsProx || useAmateurProx2) {
                        ChirpRbClientParams(
                            lat = 0.0,
                            lon = 0.0,
                            distKm = 0.0,
                            filterText = chirpFilter,
                            openOnly = openOnly,
                        )
                    } else {
                        ChirpRbClientParams(
                            lat = lat,
                            lon = lon,
                            distKm = distMiles * 1.609344,
                            filterText = chirpFilter,
                            openOnly = openOnly,
                        )
                    }
                    val filtered = ChirpRepeaterBookFilters.apply(objs, params)
                    filtered.map { RepeaterBookJsonRow(it, selected = false) }
                }
            }
            binding.progressSearch.visibility = View.GONE
            binding.btnSearch.isEnabled = true

            result.onSuccess { rows ->
                allRows.clear()
                allRows.addAll(rows)
                binding.layoutFilter.isVisible = rows.isNotEmpty()
                binding.layoutResultsActions.isVisible = rows.isNotEmpty()
                binding.editFilter.setText("")
                applyQuickFilter()
                if (rows.isNotEmpty()) {
                    scrollResultsSectionIntoView()
                }
                if (rows.isEmpty()) {
                    binding.textEmptyResults.isVisible = true
                    binding.recyclerResults.isVisible = false
                } else {
                    binding.textEmptyResults.isVisible = false
                    binding.recyclerResults.isVisible = true
                }
                updateImportButton()
            }.onFailure { e ->
                val act = this@ChirpRepeaterBookSearchActivity
                if (e is RepeaterBookApiException && e.statusCode == 401) {
                    val detail = buildString {
                        append(getString(R.string.repeaterbook_search_failed, e.message ?: ""))
                        val body = e.errorBody?.trim().orEmpty()
                        if (body.isNotEmpty()) {
                            append("\n\n")
                            append(body.take(400))
                        }
                        append("\n\n")
                        append(getString(R.string.repeaterbook_error_401_hint))
                    }
                    AlertDialog.Builder(act)
                        .setTitle(R.string.repeaterbook_error_401_title)
                        .setMessage(detail)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    Toast.makeText(
                        act,
                        getString(R.string.repeaterbook_search_failed, e.message ?: e.toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun selectAllVisible() {
        visibleRows.forEach { it.selected = true }
        adapter.notifyDataSetChanged()
        updateImportButton()
    }

    /** Scrolls so the results filter / list area is reachable after a tall search form. */
    private fun scrollResultsSectionIntoView() {
        if (!binding.layoutFilter.isVisible) return
        binding.scrollContent.post {
            binding.scrollContent.smoothScrollTo(0, binding.layoutFilter.top)
        }
    }

    private fun applyQuickFilter() {
        val q = binding.editFilter.text?.toString()?.trim()?.lowercase().orEmpty()
        visibleRows.clear()
        if (q.isEmpty()) {
            visibleRows.addAll(allRows)
        } else {
            allRows.filterTo(visibleRows) { it.matchesQuickFilter(q) }
        }
        adapter.notifyDataSetChanged()
        binding.textEmptyResults.isVisible = visibleRows.isEmpty() && allRows.isNotEmpty()
        binding.recyclerResults.isVisible = visibleRows.isNotEmpty()
    }

    private fun updateImportButton() {
        val n = allRows.count { it.selected }
        binding.btnImport.isEnabled = n > 0
        binding.btnImport.text = if (n > 0) {
            getString(R.string.repeaterbook_import_selected) + " ($n)"
        } else {
            getString(R.string.repeaterbook_import_selected)
        }
    }

    private fun importSelected() {
        val fmConv = binding.checkFmConv.isChecked
        val selected = allRows.filter { it.selected }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.repeaterbook_select_one, Toast.LENGTH_SHORT).show()
            return
        }
        val pairs = selected.mapNotNull { row ->
            val ch = RepeaterBookToChannelMapper.fromJson(row.json) ?: return@mapNotNull null
            val analog = row.json.optString("FM Analog", "").equals("Yes", ignoreCase = true)
            val chOut = if (fmConv && analog) ch.copy(mode = "FM") else ch
            row to chOut
        }
        if (pairs.isEmpty()) {
            Toast.makeText(this, R.string.repeaterbook_no_results, Toast.LENGTH_LONG).show()
            return
        }
        val channels = pairs.map { it.second }
        val comments = pairs.map { RepeaterBookToChannelMapper.commentLine(it.first.json) }
        val csv = ChirpCsvExporter.export(channels)
        startActivity(
            Intent(this, ChirpImportActivity::class.java).apply {
                putExtra(ChirpImportActivity.EXTRA_CSV_TEXT, csv)
                putExtra(ChirpImportActivity.EXTRA_COMMENTS, ArrayList(comments))
            },
        )
        finish()
    }
}

