package com.redelf.commons.connectivity.indicator.view.dialog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.redelf.commons.connectivity.indicator.R
import com.redelf.commons.connectivity.indicator.AvailableService
import com.redelf.commons.connectivity.indicator.stateful.AvailableStatefulServicesBuilder
import com.redelf.commons.connectivity.indicator.view.ConnectivityIndicator
import com.redelf.commons.dismissal.Dismissable
import com.redelf.commons.extensions.recordException
import com.redelf.commons.lifecycle.TerminationAsync
import com.redelf.commons.lifecycle.TerminationSynchronized
import com.redelf.commons.logging.Console
import com.redelf.commons.net.connectivity.Reconnect
import java.util.concurrent.CopyOnWriteArraySet

class ServicesStatesDialogAdapter(

    private val services: List<AvailableService>,
    private val layout: Int = R.layout.layout_services_states_dialog_adapter,
    private val serviceCallback: ServicesStatesDialogCallback

) : RecyclerView.Adapter<ServicesStatesDialogAdapter.ViewHolder>(), Dismissable {

    private val servicesObjects = CopyOnWriteArraySet<AvailableService>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        val title = view.findViewById<TextView?>(R.id.title)
        val refresh = view.findViewById<ImageButton?>(R.id.refresh)
        val bottomSeparator = view.findViewById<View?>(R.id.bottom_separator)
        val indicator = view.findViewById<ConnectivityIndicator?>(R.id.indicator)
    }

    override fun dismiss() {

        servicesObjects.forEach { service ->

            if (service is TerminationAsync) {

                service.terminate()

            } else if (service is TerminationSynchronized) {

                service.terminate()

            } else {

                val msg = "Service cannot be terminated ${service.javaClass.simpleName}"
                val e = IllegalStateException(msg)
                recordException(e)
            }
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {

        val view = LayoutInflater.from(viewGroup.context).inflate(layout, viewGroup, false)

        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {

        val service = services[position]

        viewHolder.refresh?.setOnClickListener {

            if (service is Reconnect) {

                serviceCallback.onService(service)
            }
        }

        viewHolder.refresh?.isEnabled = service is Reconnect

        if (service is Reconnect) {

            viewHolder.refresh?.visibility = View.VISIBLE

        } else {

            viewHolder.refresh?.visibility = View.INVISIBLE
        }

        viewHolder.title?.text = service.getWho()

        val origin = this@ServicesStatesDialogAdapter::class.java.simpleName

        val builder = AvailableStatefulServicesBuilder(origin)
            .addService(service::class.java)
            .setDebug(true)

        viewHolder.indicator?.origin = origin
        viewHolder.indicator?.setServices(builder)

        servicesObjects.addAll(

            viewHolder.indicator?.getServices()?.getServiceInstances() ?: emptyList()
        )

        if (position < services.size - 1) {

            viewHolder.bottomSeparator?.visibility = View.VISIBLE

        } else {

            viewHolder.bottomSeparator?.visibility = View.INVISIBLE
        }
    }

    override fun getItemCount() = services.size
}