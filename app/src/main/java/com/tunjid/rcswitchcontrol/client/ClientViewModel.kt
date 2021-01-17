package com.tunjid.rcswitchcontrol.client

import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.jakewharton.rx.replayingShare
import com.rcswitchcontrol.protocols.models.Payload
import com.tunjid.androidx.communications.nsd.NsdHelper
import com.tunjid.rcswitchcontrol.common.Mutation
import com.tunjid.rcswitchcontrol.common.Mutator
import com.tunjid.rcswitchcontrol.common.filterIsInstance
import com.tunjid.rcswitchcontrol.common.onErrorComplete
import com.tunjid.rcswitchcontrol.common.serialize
import com.tunjid.rcswitchcontrol.common.toLiveData
import com.tunjid.rcswitchcontrol.di.AppBroadcaster
import com.tunjid.rcswitchcontrol.di.AppBroadcasts
import com.tunjid.rcswitchcontrol.models.Broadcast
import com.tunjid.rcswitchcontrol.models.Status
import com.tunjid.rcswitchcontrol.services.hopSchedulers
import io.reactivex.Flowable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.processors.PublishProcessor
import io.reactivex.rxkotlin.addTo
import java.io.PrintWriter
import java.net.Socket
import javax.inject.Inject

data class State(
    val status: Status = Status.Disconnected,
    val serviceName: String? = null,
    val inBackground: Boolean = true,
    val isStopped: Boolean = false
)

sealed class Input {
    data class Connect(val service: NsdServiceInfo) : Input()
    data class Send(val payload: Payload) : Input()
    data class ContextChanged(val inBackground: Boolean) : Input()
}

private sealed class Output {
    sealed class Connection(val status: Status) : Output() {
        data class Connected(val serviceName: String, val writer: PrintWriter) : Connection(status = Status.Connected)
        data class Connecting(val serviceName: String) : Connection(status = Status.Connecting)
        object Disconnected : Connection(status = Status.Disconnected)
    }

    data class Response(val data: String) : Output()
}

private data class Write(
    val data: String,
    val writer: PrintWriter?
)

private val Write.isValid get() = writer != null && !writer.checkError()

private fun Write.print() = writer?.println(data)?.also { Log.i("TEST", "WROTE: $data") } ?: Unit

class ClientViewModel @Inject constructor(
    broadcaster: AppBroadcaster,
    broadcasts: AppBroadcasts,
) : ViewModel() {

    val state: LiveData<State>

    private val processor = PublishProcessor.create<Input>()
    private val disposable = CompositeDisposable()

    init {
        val inputs = processor
            .hopSchedulers()
            .replayingShare()

        val outputs = inputs
            .filterIsInstance<Input.Connect>()
            .map(Input.Connect::service)
            .doOnNext { Log.i("TEST", "PROSPECTIVE REQ") }
            .onBackpressureDrop()
            .doOnNext { Log.i("TEST", "GOING TO CONNECT") }
            .concatMap(NsdServiceInfo::outputs)
            .replayingShare()

        val connections = outputs
            .filterIsInstance<Output.Connection>()
            .replayingShare()

        val backingState = Flowable.merge(
            broadcasts.filterIsInstance<Broadcast.ClientNsd.Stop>()
                .map { Mutation { copy(isStopped = true) } },
            connections
                .map(Output.Connection::mutation),
            inputs
                .filterIsInstance<Input.ContextChanged>()
                .map(Input.ContextChanged::inBackground)
                .map { Mutation { copy(inBackground = it) } },
        )
            .scan(State(), Mutator::mutate)
            .replayingShare()

        state = backingState
            .distinctUntilChanged()
            .toLiveData()

        inputs
            .filterIsInstance<Input.Send>()
            .map(Input.Send::payload)
            .map(Payload::serialize)
            .withLatestFrom(connections) { data, connection ->
                Write(data = data, writer = when (connection) {
                    is Output.Connection.Connected -> connection.writer
                    is Output.Connection.Connecting,
                    Output.Connection.Disconnected -> null
                })
            }
            .filter(Write::isValid)
            .subscribe(Write::print)
            .addTo(disposable)

        outputs
            .filterIsInstance<Output.Response>()
            .map(Output.Response::data)
            .doOnNext { Log.i("TEST", "SERVER SAYS: $it") }
            .map(Broadcast.ClientNsd::ServerResponse)
            .subscribe(broadcaster)
            .addTo(disposable)

        backingState
            .map(State::status)
            .distinctUntilChanged()
            .map(Broadcast.ClientNsd::ConnectionStatus)
            .subscribe(broadcaster)
            .addTo(disposable)
    }

    override fun onCleared() = disposable.clear()

    fun accept(input: Input) = processor.onNext(input)
}

private fun NsdServiceInfo.outputs(): Flowable<Output> =
    Flowable.defer {
        Log.i("TEST", "OPENING SOCKET")
        val socket = Socket(host, port)
        val outWriter = NsdHelper.createPrintWriter(socket)
        val reader = NsdHelper.createBufferedReader(socket)

        Flowable.fromCallable<Output> {
            val input = reader.readLine()
            if (input == "Bye.") socket.close()
            Output.Response(data = input)
        }
            .repeatUntil(socket::isClosed)
            .doFinally(socket::close)
            .onErrorComplete()
            .startWith(Output.Connection.Connected(serviceName, writer = outWriter))
            .concatWith(Flowable.just(Output.Connection.Disconnected))
    }
        .onErrorComplete()
        .startWith(Output.Connection.Connecting(serviceName))
        .hopSchedulers()

private fun Output.Connection.mutation(): Mutation<State> =
    Mutation {
        copy(status = this@mutation.status, serviceName = when (this@mutation) {
            is Output.Connection.Connected -> this@mutation.serviceName
            is Output.Connection.Connecting -> this@mutation.serviceName
            Output.Connection.Disconnected -> null
        })
    }