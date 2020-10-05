/* Copyright 2019 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky.components.compose

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.keylesspalace.tusky.adapter.ComposeAutoCompleteAdapter
import com.keylesspalace.tusky.components.common.CommonComposeViewModel
import com.keylesspalace.tusky.components.common.MediaUploader
import com.keylesspalace.tusky.components.common.UploadEvent
import com.keylesspalace.tusky.components.common.mutableLiveData
import com.keylesspalace.tusky.components.compose.ComposeActivity.QueuedMedia
import com.keylesspalace.tusky.components.search.SearchType
import com.keylesspalace.tusky.db.AccountManager
import com.keylesspalace.tusky.db.AppDatabase
import com.keylesspalace.tusky.db.InstanceEntity
import com.keylesspalace.tusky.entity.*
import com.keylesspalace.tusky.entity.Status
import com.keylesspalace.tusky.network.MastodonApi
import com.keylesspalace.tusky.service.ServiceClient
import com.keylesspalace.tusky.service.TootToSend
import com.keylesspalace.tusky.util.*
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.Singles
import retrofit2.Response
import java.util.*
import javax.inject.Inject

class ComposeViewModel
@Inject constructor(
        private val api: MastodonApi,
        private val accountManager: AccountManager,
        private val mediaUploader: MediaUploader,
        private val serviceClient: ServiceClient,
        private val saveTootHelper: SaveTootHelper,
        private val db: AppDatabase
) : CommonComposeViewModel(api, accountManager, mediaUploader, db) {

    private var replyingStatusAuthor: String? = null
    private var replyingStatusContent: String? = null
    internal var startingText: String? = null
    private var savedTootUid: Int = 0
    private var startingContentWarning: String = ""
    private var inReplyToId: String? = null
    private var startingVisibility: Status.Visibility = Status.Visibility.UNKNOWN
    private var contentWarningStateChanged: Boolean = false
    public var formattingSyntax: String = ""
    private var modifiedInitialState: Boolean = false

    val markMediaAsSensitive =
            mutableLiveData(accountManager.activeAccount?.defaultMediaSensitivity ?: false)

    fun toggleMarkSensitive() {
        this.markMediaAsSensitive.value = !this.markMediaAsSensitive.value!!
    }

    val statusVisibility = mutableLiveData(Status.Visibility.UNKNOWN)
    val showContentWarning = mutableLiveData(false)
    val setupComplete = mutableLiveData(false)
    val poll: MutableLiveData<NewPoll?> = mutableLiveData(null)
    val scheduledAt: MutableLiveData<String?> = mutableLiveData(null)

    fun didChange(content: String?, contentWarning: String?): Boolean {

        val textChanged = !(content.isNullOrEmpty()
                || startingText?.startsWith(content.toString()) ?: false)

        val contentWarningChanged = showContentWarning.value!!
                && !contentWarning.isNullOrEmpty()
                && !startingContentWarning.startsWith(contentWarning.toString())
        val mediaChanged = !media.value.isNullOrEmpty()
        val pollChanged = poll.value != null

        return modifiedInitialState || textChanged || contentWarningChanged || mediaChanged || pollChanged
    }

    fun contentWarningChanged(value: Boolean) {
        showContentWarning.value = value
        contentWarningStateChanged = true
    }

    fun deleteDraft() {
        saveTootHelper.deleteDraft(this.savedTootUid)
    }

    fun saveDraft(content: String, contentWarning: String) {
        val mediaUris = mutableListOf<String>()
        val mediaDescriptions = mutableListOf<String?>()
        for (item in media.value!!) {
            mediaUris.add(item.uri.toString())
            mediaDescriptions.add(item.description)
        }
        saveTootHelper.saveToot(
                content,
                contentWarning,
                null,
                mediaUris,
                mediaDescriptions,
                savedTootUid,
                inReplyToId,
                replyingStatusContent,
                replyingStatusAuthor,
                statusVisibility.value!!,
                poll.value,
                formattingSyntax
        )
    }

    /**
     * Send status to the server.
     * Uses current state plus provided arguments.
     * @return LiveData which will signal once the screen can be closed or null if there are errors
     */
    fun sendStatus(
            content: String,
            spoilerText: String,
            preview: Boolean
    ): LiveData<Unit> {
        return media
                .filter { items -> items.all { it.uploadPercent == -1 } }
                .map {
                    val mediaIds = ArrayList<String>()
                    val mediaUris = ArrayList<Uri>()
                    val mediaDescriptions = ArrayList<String>()
                    for (item in media.value!!) {
                        mediaIds.add(item.id!!)
                        mediaUris.add(item.uri)
                        mediaDescriptions.add(item.description ?: "")
                    }

                    val tootToSend = TootToSend(
                            content,
                            spoilerText,
                            statusVisibility.value!!.serverString(),
                            mediaUris.isNotEmpty() && (markMediaAsSensitive.value!! || showContentWarning.value!!),
                            mediaIds,
                            mediaUris.map { it.toString() },
                            mediaDescriptions,
                            scheduledAt = scheduledAt.value,
                            inReplyToId = inReplyToId,
                            poll = poll.value,
                            replyingStatusContent = null,
                            replyingStatusAuthorUsername = null,
                            formattingSyntax = formattingSyntax,
                            preview = preview,
                            savedJsonUrls = null,
                            accountId = accountManager.activeAccount!!.id,
                            savedTootUid = 0,
                            idempotencyKey = randomAlphanumericString(16),
                            retries = 0
                    )
                    serviceClient.sendToot(tootToSend)
                }
    }

    override fun onCleared() {
        for (uploadDisposable in mediaToDisposable.values) {
            uploadDisposable.dispose()
        }
        super.onCleared()
    }

    fun setup(composeOptions: ComposeActivity.ComposeOptions?) {
        super.setup()
        val preferredVisibility = accountManager.activeAccount!!.defaultPostPrivacy

        val replyVisibility = composeOptions?.replyVisibility ?: Status.Visibility.UNKNOWN
        startingVisibility = Status.Visibility.byNum(
                preferredVisibility.num.coerceAtLeast(replyVisibility.num))

        inReplyToId = composeOptions?.inReplyToId
        modifiedInitialState = composeOptions?.modifiedInitialState == true

        val contentWarning = composeOptions?.contentWarning
        if (contentWarning != null) {
            startingContentWarning = contentWarning
        }
        if (!contentWarningStateChanged) {
            showContentWarning.value = !contentWarning.isNullOrBlank()
        }

        // recreate media list
        // when coming from SavedTootActivity
        val loadedDraftMediaUris = composeOptions?.mediaUrls
        val loadedDraftMediaDescriptions: List<String?>? = composeOptions?.mediaDescriptions
        if (loadedDraftMediaUris != null && loadedDraftMediaDescriptions != null) {
            loadedDraftMediaUris.zip(loadedDraftMediaDescriptions)
                    .forEach { (uri, description) ->
                        pickMedia(uri.toUri(), null).observeForever { errorOrItem ->
                            if (errorOrItem.isRight() && description != null) {
                                updateDescription(errorOrItem.asRight().localId, description)
                            }
                        }
                    }
        } else composeOptions?.mediaAttachments?.forEach { a ->
            // when coming from redraft
            val mediaType = when (a.type) {
                Attachment.Type.VIDEO, Attachment.Type.GIFV -> QueuedMedia.Type.VIDEO
                Attachment.Type.UNKNOWN, Attachment.Type.IMAGE -> QueuedMedia.Type.IMAGE
                Attachment.Type.AUDIO -> QueuedMedia.Type.AUDIO
            }
            addUploadedMedia(a.id, mediaType, a.url.toUri(), a.description)
        }


        savedTootUid = composeOptions?.savedTootUid ?: 0
        startingText = composeOptions?.tootText


        val tootVisibility = composeOptions?.visibility ?: Status.Visibility.UNKNOWN
        if (tootVisibility.num != Status.Visibility.UNKNOWN.num) {
            startingVisibility = tootVisibility
        }
        statusVisibility.value = startingVisibility
        val mentionedUsernames = composeOptions?.mentionedUsernames
        if (mentionedUsernames != null) {
            val builder = StringBuilder()
            for (name in mentionedUsernames) {
                builder.append('@')
                builder.append(name)
                builder.append(' ')
            }
            startingText = builder.toString()
        }


        scheduledAt.value = composeOptions?.scheduledAt

        composeOptions?.sensitive?.let { markMediaAsSensitive.value = it }

        val poll = composeOptions?.poll
        if (poll != null && composeOptions.mediaAttachments.isNullOrEmpty()) {
            this.poll.value = poll
        }
        replyingStatusContent = composeOptions?.replyingStatusContent
        replyingStatusAuthor = composeOptions?.replyingStatusAuthor
        
        formattingSyntax = composeOptions?.formattingSyntax ?: accountManager.activeAccount!!.defaultFormattingSyntax
    }

    fun updatePoll(newPoll: NewPoll) {
        poll.value = newPoll
    }

    fun updateScheduledAt(newScheduledAt: String?) {
        scheduledAt.value = newScheduledAt
    }

    private companion object {
        const val TAG = "ComposeViewModel"
    }
}
