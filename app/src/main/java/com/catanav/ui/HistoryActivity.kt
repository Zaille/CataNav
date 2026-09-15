package com.catanav.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.catanav.CataNavApp
import com.catanav.data.TripEntity
import com.catanav.databinding.ActivityHistoryBinding
import com.catanav.databinding.ItemTripBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val adapter = TripAdapter { trip ->
        startActivity(
            Intent(this, TripDetailActivity::class.java).putExtra(TripDetailActivity.EXTRA_TRIP_ID, trip.id),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tripList.layoutManager = LinearLayoutManager(this)
        binding.tripList.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CataNavApp.instance.locator.repository.trips().collect {
                    adapter.submit(it)
                }
            }
        }
    }

    private class TripAdapter(
        private val onClick: (TripEntity) -> Unit,
    ) : RecyclerView.Adapter<TripAdapter.Holder>() {

        private val items = mutableListOf<TripEntity>()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

        fun submit(trips: List<TripEntity>) {
            items.clear()
            items.addAll(trips)
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemTripBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemTripBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val trip = items[position]
            holder.binding.tripName.text = trip.name
            val status = if (trip.endTime == null) {
                "in progress / interrupted"
            } else {
                val minutes = (trip.endTime - trip.startTime) / 60_000
                "${dateFormat.format(Date(trip.startTime))} — $minutes min"
            }
            holder.binding.tripMeta.text = status
            holder.binding.root.setOnClickListener { onClick(trip) }
        }
    }
}
