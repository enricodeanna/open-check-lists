package eu.studiodeanna.openchecklists

/**
 * User-facing texts produced below the UI: sync errors and the like. The UI picks the language by
 * setting [current]; a message is worded when it is raised, so errors already shown keep theirs.
 */
interface Messages {
    val newList: String
    val syncFailed: String
    val chooseList: String
    val noListsInFolder: String
    val driveNotSetUp: String
    val keptChanging: String
    val notAShareLink: String
    val notAList: String
    val newerVersion: String

    fun cantReach(server: String): String
    val cantReachGoogle: String
    val cantReachDrive: String

    fun folderNameTaken(title: String): String
    val shareNeedsPassword: String
    val shareNotFound: String
    val shareReadOnly: String
    val cannotCreate: String
    fun nextcloudRefusedSave(detail: String?): String
    val nextcloudRefusedAccess: String
    fun nextcloudAnswered(status: String): String
    fun withNextcloudDetail(message: String, detail: String): String

    val googleAppFile: String
    val driveUnexpected: String
    fun driveCreatedNotShared(detail: String?): String
    fun driveNameTaken(title: String): String
    val driveNotFound: String
    fun driveReadOnly(detail: String?): String
    fun driveAnswered(status: Int, detail: String?): String

    val signInToSync: String
    val signInCancelled: String
    fun signInFailed(reason: String): String
    val signInMismatch: String
    val signInNoCode: String
    val signInRetry: String
    val signInTimedOut: String
    val signInCouldNotStart: String
    val openAppToSignIn: String
    fun signInUnavailable(reason: String?): String

    companion object {
        var current: Messages = EnglishMessages
    }
}

object EnglishMessages : Messages {
    override val newList = "New list"
    override val syncFailed = "Sync failed."
    override val chooseList = "Choose which list to open."
    override val noListsInFolder = "There are no lists in this shared folder yet."
    override val driveNotSetUp = "Google Drive is not set up in this build of the app."
    override val keptChanging = "The shared list kept changing while syncing. It will retry."
    override val notAShareLink = "Not a share link this app can use. Paste a Nextcloud or Google Drive link."
    override val notAList = "This file is not a list from Open Check Lists."
    override val newerVersion = "This list was saved by a newer version of Open Check Lists. Update the app to open it."

    override fun cantReach(server: String) = "Can't reach $server. Check the connection."
    override val cantReachGoogle = "Can't reach Google. Check the connection."
    override val cantReachDrive = "Can't reach Google Drive. Check the connection."

    override fun folderNameTaken(title: String) =
        "This folder already has too many lists called “$title”. Pick another name."
    override val shareNeedsPassword = "The share needs a password, or the password is wrong."
    override val shareNotFound = "Share not found. The link may have expired or been unshared."
    override val shareReadOnly = "This link is read-only. In Nextcloud, turn on “Allow editing” for the share."
    override val cannotCreate =
        "This link can edit files but not add new ones, so the list file can't be created in the folder. " +
            "In Nextcloud, set the folder link to allow upload and editing (the server admin must also allow " +
            "public uploads under Administration → Sharing). Or share a single empty file instead."
    override fun nextcloudRefusedSave(detail: String?) =
        "Nextcloud refused to save the list" + (detail?.let { ": $it" } ?: ".")
    override val nextcloudRefusedAccess = "Nextcloud refused access to this share."
    override fun nextcloudAnswered(status: String) = "Nextcloud answered $status."
    override fun withNextcloudDetail(message: String, detail: String) = "$message (Nextcloud: $detail)"

    override val googleAppFile = "This is a Google Docs, Sheets or other Google file. Share a plain file instead."
    override val driveUnexpected = "Google Drive gave an unexpected answer."
    override fun driveCreatedNotShared(detail: String?) =
        "The file was created in your Drive, but your account may not share it with “anyone with the link” " +
            "(common for work accounts). ${detail.orEmpty()}"
    override fun driveNameTaken(title: String) =
        "Your Drive already has too many lists called “$title”. Pick another name."
    override val driveNotFound =
        "File not found, or this Google account can't see it. Ask the owner to share it with “Anyone with the link”."
    override fun driveReadOnly(detail: String?) =
        "This Google account can view the file but not edit it. Ask the owner to make link sharing “Editor”." +
            (detail?.let { " ($it)" } ?: "")
    override fun driveAnswered(status: Int, detail: String?) =
        "Google Drive answered $status" + (detail?.let { ": $it" } ?: ".")

    override val signInToSync = "Sign in to Google to sync this list."
    override val signInCancelled = "Sign-in was cancelled."
    override fun signInFailed(reason: String) = "Google sign-in failed: $reason"
    override val signInMismatch = "Google sign-in failed: the reply did not match."
    override val signInNoCode = "Google sign-in failed: no code in the reply."
    override val signInRetry = "Google sign-in failed. Try again."
    override val signInTimedOut = "Google sign-in timed out. Try again."
    override val signInCouldNotStart = "Google sign-in could not start."
    override val openAppToSignIn = "Open the app to sign in to Google."
    override fun signInUnavailable(reason: String?) = "Google sign-in is not available: $reason"
}

object ItalianMessages : Messages {
    override val newList = "Nuova lista"
    override val syncFailed = "Sincronizzazione non riuscita."
    override val chooseList = "Scegli quale lista aprire."
    override val noListsInFolder = "Questa cartella condivisa non contiene ancora liste."
    override val driveNotSetUp = "Google Drive non è configurato in questa versione dell'app."
    override val keptChanging = "La lista condivisa continuava a cambiare durante la sincronizzazione. Verrà ritentata."
    override val notAShareLink = "Questo non è un link di condivisione utilizzabile. Incolla un link di Nextcloud o Google Drive."
    override val notAList = "Questo file non è una lista di Open Check Lists."
    override val newerVersion = "Questa lista è stata salvata da una versione più recente di Open Check Lists. Aggiorna l'app per aprirla."

    override fun cantReach(server: String) = "Impossibile raggiungere $server. Controlla la connessione."
    override val cantReachGoogle = "Impossibile raggiungere Google. Controlla la connessione."
    override val cantReachDrive = "Impossibile raggiungere Google Drive. Controlla la connessione."

    override fun folderNameTaken(title: String) =
        "Questa cartella contiene già troppe liste chiamate “$title”. Scegli un altro nome."
    override val shareNeedsPassword = "La condivisione richiede una password, oppure la password è sbagliata."
    override val shareNotFound = "Condivisione non trovata. Il link potrebbe essere scaduto o revocato."
    override val shareReadOnly = "Questo link è di sola lettura. In Nextcloud, attiva “Consenti modifica” per la condivisione."
    override val cannotCreate =
        "Questo link può modificare i file ma non aggiungerne di nuovi, quindi il file della lista non può essere creato " +
            "nella cartella. In Nextcloud, imposta il link della cartella per consentire caricamento e modifica " +
            "(l'amministratore deve anche consentire i caricamenti pubblici in Amministrazione → Condivisione). " +
            "Oppure condividi un singolo file vuoto."
    override fun nextcloudRefusedSave(detail: String?) =
        "Nextcloud ha rifiutato di salvare la lista" + (detail?.let { ": $it" } ?: ".")
    override val nextcloudRefusedAccess = "Nextcloud ha negato l'accesso a questa condivisione."
    override fun nextcloudAnswered(status: String) = "Nextcloud ha risposto $status."
    override fun withNextcloudDetail(message: String, detail: String) = "$message (Nextcloud: $detail)"

    override val googleAppFile = "Questo è un file Google Documenti, Fogli o simile. Condividi invece un file normale."
    override val driveUnexpected = "Google Drive ha dato una risposta inattesa."
    override fun driveCreatedNotShared(detail: String?) =
        "Il file è stato creato nel tuo Drive, ma il tuo account potrebbe non poterlo condividere con " +
            "“chiunque abbia il link” (succede spesso con gli account di lavoro). ${detail.orEmpty()}"
    override fun driveNameTaken(title: String) =
        "Il tuo Drive contiene già troppe liste chiamate “$title”. Scegli un altro nome."
    override val driveNotFound =
        "File non trovato, oppure questo account Google non può vederlo. Chiedi al proprietario di condividerlo con " +
            "“Chiunque abbia il link”."
    override fun driveReadOnly(detail: String?) =
        "Questo account Google può vedere il file ma non modificarlo. Chiedi al proprietario di impostare la " +
            "condivisione tramite link su “Editor”." + (detail?.let { " ($it)" } ?: "")
    override fun driveAnswered(status: Int, detail: String?) =
        "Google Drive ha risposto $status" + (detail?.let { ": $it" } ?: ".")

    override val signInToSync = "Accedi a Google per sincronizzare questa lista."
    override val signInCancelled = "Accesso annullato."
    override fun signInFailed(reason: String) = "Accesso a Google non riuscito: $reason"
    override val signInMismatch = "Accesso a Google non riuscito: la risposta non corrisponde."
    override val signInNoCode = "Accesso a Google non riuscito: nessun codice nella risposta."
    override val signInRetry = "Accesso a Google non riuscito. Riprova."
    override val signInTimedOut = "Tempo scaduto per l'accesso a Google. Riprova."
    override val signInCouldNotStart = "Impossibile avviare l'accesso a Google."
    override val openAppToSignIn = "Apri l'app per accedere a Google."
    override fun signInUnavailable(reason: String?) = "L'accesso a Google non è disponibile: $reason"
}
