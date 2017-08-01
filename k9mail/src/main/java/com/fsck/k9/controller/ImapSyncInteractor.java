package com.fsck.k9.controller;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fsck.k9.Account;
import com.fsck.k9.Account.Expunge;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Store;
import com.fsck.k9.mail.store.imap.ImapFolder;
import com.fsck.k9.mail.store.imap.QresyncParamResponse;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.LocalStore;
import com.fsck.k9.notification.NotificationController;
import timber.log.Timber;


class ImapSyncInteractor {

    private final Account account;
    private final String folderName;
    private LocalFolder localFolder;
    private ImapFolder imapFolder;
    private final MessagingListener listener;
    private final MessagingController controller;

    ImapSyncInteractor(Account account, String folderName, MessagingListener listener, MessagingController controller) {
        this.account = account;
        this.folderName = folderName;
        this.listener = listener;
        this.controller = controller;
    }

    void performSync(FlagSyncHelper flagSyncHelper, MessageDownloader messageDownloader,
            NotificationController notificationController, SyncHelper syncHelper) {
        for (MessagingListener l : controller.getListeners(listener)) {
            l.synchronizeMailboxStarted(account, folderName);
        }

        Exception commandException = null;

        try {
            Timber.d("SYNC: About to process pending commands for account %s", account.getDescription());

            try {
                controller.processPendingCommandsSynchronous(account);
            } catch (Exception e) {
                controller.addErrorMessage(account, null, e);
                Timber.e(e, "Failure processing command, but allow message sync attempt");
                commandException = e;
            }

            initialize();
            localFolder.updateLastUid();

            if (!syncHelper.verifyOrCreateRemoteSpecialFolder(account, folderName, imapFolder, listener, controller)) {
                return;
            }

            notificationController.clearAuthenticationErrorNotification(account, true);

            QresyncParamResponse qresyncParamResponse = null;
            Timber.v("SYNC: About to open remote IMAP folder %s", folderName);
            if (localFolder.hasCachedUidValidity() && localFolder.isCachedHighestModSeqValid()) {
                qresyncParamResponse = imapFolder.openUsingQresyncParam(Folder.OPEN_MODE_RW,
                        localFolder.getUidValidity(), localFolder.getHighestModSeq());
            } else {
                imapFolder.open(Folder.OPEN_MODE_RW);
            }

            boolean qresyncEnabled = qresyncParamResponse != null;
            List<String> expungedUids = new ArrayList<>();
            if (Expunge.EXPUNGE_ON_POLL == account.getExpungePolicy()) {
                Timber.d("SYNC: Expunging folder %s:%s", account.getDescription(), folderName);
                if (qresyncEnabled) {
                    expungedUids = imapFolder.expungeUsingQresync();
                } else {
                    imapFolder.expunge();
                }
            }

            int remoteMessageCount = imapFolder.getMessageCount();
            if (remoteMessageCount < 0) {
                throw new IllegalStateException("Message count " + remoteMessageCount + " for folder " + folderName);
            }

            Timber.v("SYNC: Remote message count for folder %s is %d", folderName, remoteMessageCount);

            handleUidValidity();
            int newMessages;
            if (!qresyncEnabled) {
                NonQresyncExtensionHandler handler = new NonQresyncExtensionHandler(account, localFolder, imapFolder,
                        listener, controller);
                newMessages = handler.continueSync(messageDownloader, flagSyncHelper, syncHelper);
            } else {
                Timber.v("SYNC: QRESYNC extension found and enabled for folder %s", folderName);
                QresyncExtensionHandler handler = new QresyncExtensionHandler(account, localFolder, imapFolder,
                        listener, controller);
                newMessages = handler.continueSync(qresyncParamResponse, expungedUids, messageDownloader,
                        flagSyncHelper, syncHelper);
            }

            localFolder.setUidValidity(imapFolder.getUidValidity());
            updateHighestModSeqIfNecessary();

            int unreadMessageCount = localFolder.getUnreadMessageCount();
            for (MessagingListener l : controller.getListeners()) {
                l.folderStatusChanged(account, folderName, unreadMessageCount);
            }

            localFolder.setLastChecked(System.currentTimeMillis());
            localFolder.setStatus(null);

            Timber.d("Done synchronizing folder %s:%s @ %tc with %d new messages",
                    account.getDescription(),
                    folderName,
                    System.currentTimeMillis(),
                    newMessages);

            for (MessagingListener l : controller.getListeners(listener)) {
                l.synchronizeMailboxFinished(account, folderName, imapFolder.getMessageCount(), newMessages);
            }

            if (commandException != null) {
                String rootMessage = MessagingController.getRootCauseMessage(commandException);
                Timber.e("Root cause failure in %s:%s was '%s'",
                        account.getDescription(), folderName, rootMessage);
                localFolder.setStatus(rootMessage);
                for (MessagingListener l : controller.getListeners(listener)) {
                    l.synchronizeMailboxFailed(account, folderName, rootMessage);
                }
            }

            Timber.i("Done synchronizing folder %s:%s", account.getDescription(), folderName);

        } catch (AuthenticationFailedException e) {
            controller.handleAuthenticationFailure(account, true);
            for (MessagingListener l : controller.getListeners(listener)) {
                l.synchronizeMailboxFailed(account, folderName, "Authentication failure");
            }
        } catch (Exception e) {
            Timber.e(e, "synchronizeMailbox");
            // If we don't set the last checked, it can try too often during
            // failure conditions
            String rootMessage = MessagingController.getRootCauseMessage(e);
            if (localFolder != null) {
                try {
                    localFolder.setStatus(rootMessage);
                    localFolder.setLastChecked(System.currentTimeMillis());
                } catch (MessagingException me) {
                    Timber.e(e, "Could not set last checked on folder %s:%s",
                            account.getDescription(), localFolder.getName());
                }
            }

            for (MessagingListener l : controller.getListeners(listener)) {
                l.synchronizeMailboxFailed(account, folderName, rootMessage);
            }
            controller.notifyUserIfCertificateProblem(account, e, true);
            controller.addErrorMessage(account, null, e);
            Timber.e("Failed synchronizing folder %s:%s @ %tc", account.getDescription(), folderName,
                    System.currentTimeMillis());

        } finally {
            MessagingController.closeFolder(imapFolder);
            MessagingController.closeFolder(localFolder);
        }
    }

    private void handleUidValidity() throws MessagingException {
        long cachedUidValidity = localFolder.getUidValidity();
        long currentUidValidity = imapFolder.getUidValidity();

        if (localFolder.hasCachedUidValidity() && cachedUidValidity != currentUidValidity) {

            Timber.v("SYNC: Deleting all local messages in folder %s due to UIDVALIDITY change", localFolder);
            Set<String> localUids = localFolder.getAllMessagesAndEffectiveDates().keySet();
            List<LocalMessage> destroyedMessages = localFolder.getMessagesByUids(localUids);

            localFolder.destroyMessages(destroyedMessages);
            for (Message destroyedMessage : destroyedMessages) {
                for (MessagingListener l : controller.getListeners(listener)) {
                    l.synchronizeMailboxRemovedMessage(account, imapFolder.getName(), destroyedMessage);
                }
            }
            localFolder.invalidateHighestModSeq();
        }
    }

    private void updateHighestModSeqIfNecessary() throws MessagingException {
        long cachedHighestModSeq = localFolder.getHighestModSeq();
        long remoteHighestModSeq = imapFolder.getHighestModSeq();

        if (!imapFolder.supportsModSeq()) {
            localFolder.invalidateHighestModSeq();
        }

        if (remoteHighestModSeq > cachedHighestModSeq) {
            localFolder.setHighestModSeq(remoteHighestModSeq);
        }
    }

    private void initialize() throws MessagingException {
        if (localFolder == null || !localFolder.isOpen()) {
            Timber.v("SYNC: About to get local folder %s and open it", folderName);
            final LocalStore localStore = account.getLocalStore();
            localFolder = localStore.getFolder(folderName);
            localFolder.open(Folder.OPEN_MODE_RW);
        }

        if (imapFolder == null || !imapFolder.isOpen()) {
            Store remoteStore = account.getRemoteStore();
            Timber.v("SYNC: About to get remote folder %s", folderName);
            Folder remoteFolder = remoteStore.getFolder(folderName);
            if (!(remoteFolder instanceof ImapFolder)) {
                throw new IllegalArgumentException("A non-IMAP account was provided to ImapSyncInteractor");
            }
            imapFolder = (ImapFolder) remoteFolder;
        }
    }
}
