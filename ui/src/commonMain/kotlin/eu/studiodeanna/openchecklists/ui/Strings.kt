package eu.studiodeanna.openchecklists.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import eu.studiodeanna.openchecklists.EnglishMessages
import eu.studiodeanna.openchecklists.ItalianMessages
import eu.studiodeanna.openchecklists.Messages
import eu.studiodeanna.openchecklists.model.Action

/** The UI's texts in one language; [messages] are the matching ones raised by the shared code. */
interface Strings {
    val messages: Messages

    val appName: String get() = "Open Check Lists"
    val back: String
    val more: String
    val cancel: String
    val close: String
    val save: String
    val delete: String
    val rename: String
    val add: String
    val create: String
    val edit: String
    val name: String
    val none: String

    // Overview
    val newList: String
    val noLists: String
    val openShared: String
    /** [drive]: whether this build can use Google Drive links. */
    fun openSharedIntro(drive: Boolean): String
    val signOutOfGoogle: String
    val emptyList: String
    fun doneOf(done: Int, total: Int): String

    // A list
    val sharing: String
    val syncNow: String
    val renameList: String
    val addSection: String
    val uncheckAll: String
    val removeChecked: String
    val deleteList: String
    val signIn: String
    val chooseFile: String
    val retry: String
    val sectionNameHint: String
    val renameSection: String
    fun deleteTitle(name: String): String
    val deleteSectionMessage: String
    val deleteSharedListMessage: String
    val cantBeUndone: String
    val shareThisList: String
    fun shareHelp(drive: Boolean): String
    val sectionOptions: String
    val moveUp: String
    val moveDown: String
    val deleteSection: String
    val expandSection: String
    val collapseSection: String
    val dragToReorder: String
    val addItem: String
    val doneAdding: String
    val sharedList: String
    val anyoneWithLinkGoogle: String
    val anyoneWithLink: String
    fun keptInFolder(fileName: String): String
    fun needsSharePassword(password: String): String
    fun linkExpires(date: String): String
    val copyLink: String
    val copied: String
    val send: String
    /** What "Send" passes to another app: the link, and the password if the share has one. */
    fun sendText(link: String, password: String?): String
    val stopSyncing: String
    val undo: String
    val redo: String
    /** Shown after undoing [action], like "Undone: checked “Milk”". */
    fun undone(action: Action): String
    fun redone(action: Action): String

    // Dialogs
    val createDriveLink: String
    val createNextcloudLink: String
    fun orPasteLink(drive: Boolean): String
    val shareLink: String
    val sharePassword: String
    val connect: String
    val whichList: String
    val severalLists: String
    val editItem: String
    val section: String

    // Sync state
    val syncProblem: String
    val shared: String
    val onlyOnDevice: String
    val syncing: String
    val syncedJustNow: String
    fun syncedMinutesAgo(minutes: Long): String
    fun syncedHoursAgo(hours: Long): String
    fun syncedDaysAgo(days: Long): String

    // Nextcloud account
    val connectNextcloud: String
    val connectNextcloudIntro: String
    val nextcloudAddress: String
    val logIn: String
    val logInInBrowser: String
    val openAgain: String
    val nextcloudFolder: String
    val nextcloudFolderIntro: String
    val folderChangeNote: String
    val otherFolder: String
    val chooseFolder: String
    val chooseFolderHint: String
    val useThisFolder: String
    val newFolder: String
    val parentFolder: String
    val noSubfolders: String
    val notConnected: String
    fun connectedAs(user: String, server: String): String
    fun sharedListsGoTo(folder: String): String
    val changeFolder: String
    val disconnect: String
    val disconnectTitle: String
    val disconnectMessage: String

    // Settings
    val settings: String
    val theme: String
    val language: String
    val systemDefault: String
    val light: String
    val dark: String
}

object EnglishStrings : Strings {
    override val messages = EnglishMessages
    override val back = "Back"
    override val more = "More"
    override val cancel = "Cancel"
    override val close = "Close"
    override val save = "Save"
    override val delete = "Delete"
    override val rename = "Rename"
    override val add = "Add"
    override val create = "Create"
    override val edit = "Edit"
    override val name = "Name"
    override val none = "None"

    override val newList = "New list"
    override val noLists = "No lists yet.\nStart a new one, or open a list someone shared with you."
    override val openShared = "Open a shared list"
    override fun openSharedIntro(drive: Boolean) =
        if (drive) {
            "Paste the Nextcloud or Google Drive link you were sent. " +
                "For a Google Drive link, Google then asks you to sign in and to choose the list's file."
        } else {
            "Paste the Nextcloud link you were sent."
        }
    override val signOutOfGoogle = "Sign out of Google"
    override val emptyList = "Empty"
    override fun doneOf(done: Int, total: Int) = "$done of $total done"

    override val sharing = "Sharing"
    override val syncNow = "Sync now"
    override val renameList = "Rename list"
    override val addSection = "Add section"
    override val uncheckAll = "Uncheck all"
    override val removeChecked = "Remove checked items"
    override val deleteList = "Delete list"
    override val signIn = "Sign in"
    override val chooseFile = "Choose file"
    override val retry = "Retry"
    override val sectionNameHint = "Name, e.g. Produce"
    override val renameSection = "Rename section"
    override fun deleteTitle(name: String) = "Delete “$name”?"
    override val deleteSectionMessage = "The items in this section are deleted too, for everyone the list is shared with."
    override val deleteSharedListMessage =
        "It is removed from this device only. The shared file and other people's copies stay."
    override val cantBeUndone = "This can't be undone."
    override val shareThisList = "Share this list"
    override fun shareHelp(drive: Boolean) =
        "Keep this list in a shared file so anyone with the link can edit it.\n\n" +
            "Nextcloud: create a link below. You log in to your Nextcloud once; the people you share with need no account.\n\n" +
            (if (drive) "Google Drive: create a link below; others sign in with their Google account to edit.\n\n" else "") +
            "You can also paste a Nextcloud link you made yourself, to a folder or file shared with editing allowed."
    override val sectionOptions = "Section options"
    override val moveUp = "Move up"
    override val moveDown = "Move down"
    override val deleteSection = "Delete section"
    override val expandSection = "Expand section"
    override val collapseSection = "Collapse section"
    override val dragToReorder = "Drag to reorder"
    override val addItem = "Add item"
    override val doneAdding = "Done adding"
    override val sharedList = "Shared list"
    override val anyoneWithLinkGoogle =
        "Anyone with this link can open and edit the list in Open Check Lists, signed in with a Google account:"
    override val anyoneWithLink = "Anyone with this link can open and edit the list in Open Check Lists:"
    override fun keptInFolder(fileName: String) = "Kept in the shared folder as “$fileName”."
    override fun needsSharePassword(password: String) = "They also need the share password: $password"
    override fun linkExpires(date: String) = "Your Nextcloud ends this link on $date."
    override val copyLink = "Copy link"
    override val copied = "Copied"
    override val send = "Send…"
    override fun sendText(link: String, password: String?) =
        "Open this list in Open Check Lists: $link" + (password?.let { "\nPassword: $it" } ?: "")
    override val stopSyncing = "Stop syncing on this device"
    override val undo = "Undo"
    override val redo = "Redo"
    override fun undone(action: Action) = "Undone: ${describe(action)}"
    override fun redone(action: Action) = "Redone: ${describe(action)}"
    private fun describe(action: Action) = when (action) {
        Action.RenamedList -> "renamed the list"
        is Action.AddedSection -> "added section “${action.name}”"
        is Action.RenamedSection -> "renamed section “${action.name}”"
        is Action.MovedSection -> "moved section “${action.name}”"
        is Action.DeletedSection -> "deleted section “${action.name}”"
        is Action.AddedItem -> "added “${action.text}”"
        is Action.EditedItem -> "edited “${action.text}”"
        is Action.MovedItem -> "moved “${action.text}”"
        is Action.CheckedItem -> "checked “${action.text}”"
        is Action.UncheckedItem -> "unchecked “${action.text}”"
        is Action.DeletedItem -> "deleted “${action.text}”"
        is Action.RemovedChecked -> if (action.count == 1) "removed 1 checked item" else "removed ${action.count} checked items"
        is Action.UncheckedItems -> if (action.count == 1) "unchecked 1 item" else "unchecked ${action.count} items"
        Action.Other -> "the last change"
    }

    override val createDriveLink = "Create a Google Drive link"
    override val createNextcloudLink = "Create a Nextcloud link"
    override fun orPasteLink(drive: Boolean) = if (drive) "Or paste a Nextcloud or Google Drive link:" else "Or paste a Nextcloud link:"
    override val shareLink = "Share link"
    override val sharePassword = "Nextcloud share password"
    override val connect = "Connect"
    override val whichList = "Which list?"
    override val severalLists = "This shared folder holds several lists."
    override val editItem = "Edit item"
    override val section = "Section"

    override val syncProblem = "Sync problem"
    override val shared = "Shared"
    override val onlyOnDevice = "Only on this device"
    override val syncing = "Syncing…"
    override val syncedJustNow = "Synced just now"
    override fun syncedMinutesAgo(minutes: Long) = "Synced $minutes min ago"
    override fun syncedHoursAgo(hours: Long) = "Synced $hours h ago"
    override fun syncedDaysAgo(days: Long) = "Synced $days days ago"

    override val connectNextcloud = "Connect to Nextcloud"
    override val connectNextcloudIntro =
        "Log in to your Nextcloud once, so the app can create a share link for each list. " +
            "The people you share with need no account."
    override val nextcloudAddress = "Nextcloud address"
    override val logIn = "Log in"
    override val logInInBrowser = "Log in to Nextcloud in the browser window that opened, then come back here."
    override val openAgain = "Open again"
    override val nextcloudFolder = "Folder for shared lists"
    override val nextcloudFolderIntro =
        "Lists you share are kept in this folder in your Nextcloud, each in its own file with its own link."
    override val folderChangeNote = "Lists already shared stay where they are."
    override val otherFolder = "Choose another folder…"
    override val chooseFolder = "Choose a folder"
    override val chooseFolderHint = "Open a folder, or create a new one."
    override val useThisFolder = "Use this folder"
    override val newFolder = "New folder"
    override val parentFolder = "Up one level"
    override val noSubfolders = "No folders in here."
    override val notConnected = "Not connected."
    override fun connectedAs(user: String, server: String) = "Connected as $user on $server."
    override fun sharedListsGoTo(folder: String) = "Shared lists go in “$folder”."
    override val changeFolder = "Change folder"
    override val disconnect = "Disconnect"
    override val disconnectTitle = "Disconnect from Nextcloud?"
    override val disconnectMessage =
        "Lists already shared keep syncing through their links. The app's login is removed from your Nextcloud."

    override val settings = "Settings"
    override val theme = "Theme"
    override val language = "Language"
    override val systemDefault = "System default"
    override val light = "Light"
    override val dark = "Dark"
}

object ItalianStrings : Strings {
    override val messages = ItalianMessages
    override val back = "Indietro"
    override val more = "Altro"
    override val cancel = "Annulla"
    override val close = "Chiudi"
    override val save = "Salva"
    override val delete = "Elimina"
    override val rename = "Rinomina"
    override val add = "Aggiungi"
    override val create = "Crea"
    override val edit = "Modifica"
    override val name = "Nome"
    override val none = "Nessuna"

    override val newList = "Nuova lista"
    override val noLists = "Ancora nessuna lista.\nCreane una nuova, o apri una lista che qualcuno ha condiviso con te."
    override val openShared = "Apri una lista condivisa"
    override fun openSharedIntro(drive: Boolean) =
        if (drive) {
            "Incolla il link di Nextcloud o Google Drive che hai ricevuto. " +
                "Per un link di Google Drive, Google ti chiede poi di accedere e di scegliere il file della lista."
        } else {
            "Incolla il link di Nextcloud che hai ricevuto."
        }
    override val signOutOfGoogle = "Esci da Google"
    override val emptyList = "Vuota"
    override fun doneOf(done: Int, total: Int) = "$done di $total fatti"

    override val sharing = "Condivisione"
    override val syncNow = "Sincronizza ora"
    override val renameList = "Rinomina lista"
    override val addSection = "Aggiungi sezione"
    override val uncheckAll = "Deseleziona tutto"
    override val removeChecked = "Rimuovi gli elementi spuntati"
    override val deleteList = "Elimina lista"
    override val signIn = "Accedi"
    override val chooseFile = "Scegli file"
    override val retry = "Riprova"
    override val sectionNameHint = "Nome, es. Frutta e verdura"
    override val renameSection = "Rinomina sezione"
    override fun deleteTitle(name: String) = "Eliminare “$name”?"
    override val deleteSectionMessage =
        "Vengono eliminati anche gli elementi di questa sezione, per tutti quelli con cui la lista è condivisa."
    override val deleteSharedListMessage =
        "Viene rimossa solo da questo dispositivo. Il file condiviso e le copie degli altri restano."
    override val cantBeUndone = "L'operazione non può essere annullata."
    override val shareThisList = "Condividi questa lista"
    override fun shareHelp(drive: Boolean) =
        "Tieni questa lista in un file condiviso, così chiunque abbia il link può modificarla.\n\n" +
            "Nextcloud: crea un link qui sotto. Accedi una volta al tuo Nextcloud; chi riceve il link non ha bisogno " +
            "di un account.\n\n" +
            (if (drive) "Google Drive: crea un link qui sotto; gli altri accedono con il proprio account Google per modificarla.\n\n" else "") +
            "Puoi anche incollare un link di Nextcloud creato da te, a una cartella o a un file condiviso con la modifica consentita."
    override val sectionOptions = "Opzioni della sezione"
    override val moveUp = "Sposta su"
    override val moveDown = "Sposta giù"
    override val deleteSection = "Elimina sezione"
    override val expandSection = "Espandi la sezione"
    override val collapseSection = "Comprimi la sezione"
    override val dragToReorder = "Trascina per riordinare"
    override val addItem = "Aggiungi elemento"
    override val doneAdding = "Fine"
    override val sharedList = "Lista condivisa"
    override val anyoneWithLinkGoogle =
        "Chiunque abbia questo link può aprire e modificare la lista in Open Check Lists, accedendo con un account Google:"
    override val anyoneWithLink = "Chiunque abbia questo link può aprire e modificare la lista in Open Check Lists:"
    override fun keptInFolder(fileName: String) = "Salvata nella cartella condivisa come “$fileName”."
    override fun needsSharePassword(password: String) = "Serve anche la password della condivisione: $password"
    override fun linkExpires(date: String) = "Il tuo Nextcloud disattiva questo link il $date."
    override val copyLink = "Copia link"
    override val copied = "Copiato"
    override val send = "Invia…"
    override fun sendText(link: String, password: String?) =
        "Apri questa lista in Open Check Lists: $link" + (password?.let { "\nPassword: $it" } ?: "")
    override val stopSyncing = "Interrompi la sincronizzazione su questo dispositivo"
    override val undo = "Annulla"
    override val redo = "Ripeti"
    override fun undone(action: Action) = "Annullato: ${describe(action)}"
    override fun redone(action: Action) = "Ripetuto: ${describe(action)}"
    private fun describe(action: Action) = when (action) {
        Action.RenamedList -> "cambio del nome della lista"
        is Action.AddedSection -> "aggiunta della sezione “${action.name}”"
        is Action.RenamedSection -> "cambio del nome della sezione “${action.name}”"
        is Action.MovedSection -> "spostamento della sezione “${action.name}”"
        is Action.DeletedSection -> "eliminazione della sezione “${action.name}”"
        is Action.AddedItem -> "aggiunta di “${action.text}”"
        is Action.EditedItem -> "modifica di “${action.text}”"
        is Action.MovedItem -> "spostamento di “${action.text}”"
        is Action.CheckedItem -> "spunta di “${action.text}”"
        is Action.UncheckedItem -> "rimozione della spunta di “${action.text}”"
        is Action.DeletedItem -> "eliminazione di “${action.text}”"
        is Action.RemovedChecked ->
            if (action.count == 1) "rimozione di 1 elemento spuntato" else "rimozione di ${action.count} elementi spuntati"
        is Action.UncheckedItems -> if (action.count == 1) "deselezione di 1 elemento" else "deselezione di ${action.count} elementi"
        Action.Other -> "ultima modifica"
    }

    override val createDriveLink = "Crea un link di Google Drive"
    override val createNextcloudLink = "Crea un link di Nextcloud"
    override fun orPasteLink(drive: Boolean) =
        if (drive) "Oppure incolla un link di Nextcloud o Google Drive:" else "Oppure incolla un link di Nextcloud:"
    override val shareLink = "Link di condivisione"
    override val sharePassword = "Password della condivisione Nextcloud"
    override val connect = "Collega"
    override val whichList = "Quale lista?"
    override val severalLists = "Questa cartella condivisa contiene più liste."
    override val editItem = "Modifica elemento"
    override val section = "Sezione"

    override val syncProblem = "Problema di sincronizzazione"
    override val shared = "Condivisa"
    override val onlyOnDevice = "Solo su questo dispositivo"
    override val syncing = "Sincronizzazione…"
    override val syncedJustNow = "Sincronizzata ora"
    override fun syncedMinutesAgo(minutes: Long) = "Sincronizzata $minutes min fa"
    override fun syncedHoursAgo(hours: Long) = "Sincronizzata $hours h fa"
    override fun syncedDaysAgo(days: Long) = "Sincronizzata $days giorni fa"

    override val connectNextcloud = "Collega Nextcloud"
    override val connectNextcloudIntro =
        "Accedi una volta al tuo Nextcloud, così l'app può creare un link di condivisione per ogni lista. " +
            "Chi riceve il link non ha bisogno di un account."
    override val nextcloudAddress = "Indirizzo di Nextcloud"
    override val logIn = "Accedi"
    override val logInInBrowser = "Accedi a Nextcloud nella finestra del browser che si è aperta, poi torna qui."
    override val openAgain = "Apri di nuovo"
    override val nextcloudFolder = "Cartella per le liste condivise"
    override val nextcloudFolderIntro =
        "Le liste che condividi vengono salvate in questa cartella del tuo Nextcloud, ognuna in un proprio file " +
            "con un proprio link."
    override val folderChangeNote = "Le liste già condivise restano dove sono."
    override val otherFolder = "Scegli un'altra cartella…"
    override val chooseFolder = "Scegli una cartella"
    override val chooseFolderHint = "Apri una cartella o creane una nuova."
    override val useThisFolder = "Usa questa cartella"
    override val newFolder = "Nuova cartella"
    override val parentFolder = "Cartella superiore"
    override val noSubfolders = "Nessuna cartella qui dentro."
    override val notConnected = "Non collegato."
    override fun connectedAs(user: String, server: String) = "Collegato come $user su $server."
    override fun sharedListsGoTo(folder: String) = "Le liste condivise vanno in “$folder”."
    override val changeFolder = "Cambia cartella"
    override val disconnect = "Scollega"
    override val disconnectTitle = "Scollegare Nextcloud?"
    override val disconnectMessage =
        "Le liste già condivise continuano a sincronizzarsi tramite i loro link. L'accesso dell'app viene rimosso " +
            "dal tuo Nextcloud."

    override val settings = "Impostazioni"
    override val theme = "Tema"
    override val language = "Lingua"
    override val systemDefault = "Predefinito di sistema"
    override val light = "Chiaro"
    override val dark = "Scuro"
}

val LocalStrings = staticCompositionLocalOf<Strings> { EnglishStrings }

/** The texts for the language picked in the settings. */
val strings: Strings
    @Composable @ReadOnlyComposable get() = LocalStrings.current
