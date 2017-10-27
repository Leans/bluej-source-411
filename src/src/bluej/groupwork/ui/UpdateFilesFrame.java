/*
 This file is part of the BlueJ program.
 Copyright (C) 1999-2009,2014,2016,2017  Michael Kolling and John Rosenberg

 This program is free software; you can redistribute it and/or
 modify it under the terms of the GNU General Public License
 as published by the Free Software Foundation; either version 2
 of the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.

 This file is subject to the Classpath exception as provided in the
 LICENSE.txt file that accompanied this code.
 */
package bluej.groupwork.ui;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import bluej.Config;
import bluej.groupwork.actions.UpdateAction;
import bluej.groupwork.Repository;
import bluej.groupwork.StatusHandle;
import bluej.groupwork.StatusListener;
import bluej.groupwork.TeamStatusInfo;
import bluej.groupwork.TeamStatusInfo.Status;
import bluej.groupwork.TeamUtils;
import bluej.groupwork.TeamViewFilter;
import bluej.groupwork.TeamworkCommand;
import bluej.groupwork.TeamworkCommandResult;
import bluej.groupwork.UpdateFilter;
import bluej.pkgmgr.BlueJPackageFile;
import bluej.pkgmgr.PkgMgrFrame;
import bluej.pkgmgr.Project;
import bluej.utility.DialogManager;
import bluej.utility.FXWorker;
import bluej.utility.javafx.FXCustomizedDialog;
import bluej.utility.javafx.JavaFXUtil;
import bluej.utility.Utility;

import threadchecker.OnThread;
import threadchecker.Tag;

/**
 * A user interface for showing files to be updated
 *
 * @author Bruce Quig
 * @author Davin McCall
 * @author Amjad Altadmri
 */
@OnThread(Tag.FXPlatform)
public class UpdateFilesFrame extends FXCustomizedDialog<Void>
{
    private CheckBox includeLayoutCheckbox;
    private ActivityIndicator progressBar;
    private UpdateAction updateAction;
    private UpdateWorker updateWorker;

    private Project project;
    private Repository repository;
    private ObservableList<UpdateStatus> updateListModel;

    private Set<TeamStatusInfo> changedLayoutFiles = new HashSet<>(); // set of TeamStatusInfo
    private Set<File> forcedLayoutFiles = new HashSet<>(); // set of File

    private static UpdateStatus noFilesToUpdate = new UpdateStatus(Config.getString("team.noupdatefiles"));
    private static UpdateStatus needUpdate = new UpdateStatus(Config.getString("team.pullNeeded"));

    private boolean includeLayout = true;
    private boolean pullWithNoChanges = false;

    private final boolean isDVCS;

    public UpdateFilesFrame(Project project, Window owner)
    {
        super(owner, "team.update.title", "team-update-files");
        this.project = project;
        isDVCS = project.getTeamSettingsController().isDVCS();
        buildUI();
        prepareButtonPane();
        DialogManager.centreDialog(this);
    }

    @Override
    protected Node wrapButtonBar(Node original)
    {
        //TODO move buttons to bottom left
        return super.wrapButtonBar(original);
    }

    /**
     * Create the user-interface for the error display dialog.
     */
    private void buildUI()
    {
        VBox mainPane = new VBox();
        JavaFXUtil.addStyleClass(mainPane, "main-pane");/////

        updateListModel = FXCollections.observableArrayList();
        Label updateFilesLabel = new Label(Config.getString("team.update.files"));
        ListView<UpdateStatus> updateFiles = new ListView<>(updateListModel);
        if (isDVCS) {
            updateFiles.setCellFactory(param -> new FileRendererCell(project, true));//
        } else {
            updateFiles.setCellFactory(param -> new FileRendererCell(project));//
        }
        updateFiles.setDisable(true);

        ScrollPane updateFileScrollPane = new ScrollPane(updateFiles);
        updateFileScrollPane.setFitToWidth(true);
        updateFileScrollPane.setFitToHeight(true);


        updateAction = new UpdateAction(this);
        Button updateButton = new Button();
        updateAction.useButton(PkgMgrFrame.getMostRecent(), updateButton);
        updateButton.requestFocus();

        progressBar = new ActivityIndicator();
        progressBar.setRunning(false);

        includeLayoutCheckbox = new CheckBox(Config.getString("team.update.includelayout"));
        includeLayoutCheckbox.setDisable(true);
        includeLayoutCheckbox.setOnAction(event -> {
            CheckBox layoutCheck = (CheckBox)event.getSource();
            includeLayout = layoutCheck.isSelected();
            resetForcedFiles();
            if (includeLayout) {
                addModifiedLayouts();
                if(updateButton.isDisabled()) {
                    updateAction.setEnabled(true);
                }
            }
            // unselected
            else {
                removeModifiedLayouts();
                if(isUpdateListEmpty()) {
                    updateAction.setEnabled(false);
                }
            }
        });

        HBox updateButtonPane = new HBox();
        JavaFXUtil.addStyleClass(updateButtonPane, "button-hbox");
        updateButtonPane.getChildren().addAll(progressBar, updateButton);

        mainPane.getChildren().addAll(updateFilesLabel, updateFileScrollPane, includeLayoutCheckbox, updateButtonPane);
        getDialogPane().setContent(mainPane);
    }

    /**
     * Create the button panel with a close button
     * @return Pane the buttonPanel
     */
    private void prepareButtonPane()
    {
        getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        this.setOnCloseRequest(event -> {
            if (updateWorker != null) {
                updateWorker.abort();
            }
            if (updateAction != null) {
                updateAction.cancel();
            }
            close();
        });
    }

    public void setVisible(boolean visible)
    {
        if (visible) {
            show();
            // we want to set update action disabled until we know that
            // there's something to update
            updateAction.setEnabled(false);
            includeLayoutCheckbox.setSelected(false);
            includeLayoutCheckbox.setDisable(true);
            changedLayoutFiles.clear();
            forcedLayoutFiles.clear();
            updateListModel.clear();

            repository = project.getRepository();

            if (repository != null) {
                try {
                    project.saveAllEditors();
                    project.saveAll();
                }
                catch (IOException ioe) {
                    String msg = DialogManager.getMessage("team-error-saving-project");
                    if (msg != null) {
                        msg = Utility.mergeStrings(msg, ioe.getLocalizedMessage());
                        String msgFinal = msg;
                        DialogManager.showErrorTextFX(this.asWindow(), msgFinal);
                    }
                }
                startProgress();
                updateWorker = new UpdateWorker();
                updateWorker.start();
            }
            else {
                hide();
            }
        }
        else {
            hide();
        }
    }

    public void reset()
    {
        updateListModel.clear();
    }

    private void removeModifiedLayouts()
    {
        // remove modified layouts from list of files shown for commit
        updateListModel.removeAll(changedLayoutFiles);

        if(updateListModel.isEmpty()) {
            updateListModel.add(noFilesToUpdate);
        }
    }

    private boolean isUpdateListEmpty()
    {
        return updateListModel.isEmpty() || updateListModel.contains(noFilesToUpdate);
    }

    /**
     * Add the modified layouts to the displayed list of files to be updated.
     */
    private void addModifiedLayouts()
    {
        if(updateListModel.contains(noFilesToUpdate)) {
            updateListModel.remove(noFilesToUpdate);
        }
    }

    /**
     * Get a set (of File) containing the layout files which need to be updated.
     */
    public Set<File> getChangedLayoutFiles()
    {
        return changedLayoutFiles.stream().map(TeamStatusInfo::getFile).collect(Collectors.toSet());
    }

    public boolean includeLayout()
    {
        return includeLayoutCheckbox != null && includeLayoutCheckbox.isSelected();
    }

    /**
     * Start the activity indicator.
     */
    public void startProgress()
    {
        progressBar.setRunning(true);
    }

    /**
     * Stop the activity indicator. Call from any thread.
     */
    public void stopProgress()
    {
        progressBar.setRunning(false);
    }

    public Project getProject()
    {
        return project;
    }

    /**
     * The layout has changed. Enable the "include layout" checkbox, etc.
     */
    private void setLayoutChanged()
    {
        includeLayoutCheckbox.setDisable(false);
        includeLayoutCheckbox.setSelected(includeLayout);
    }

    public void disableLayoutCheck()
    {
        includeLayoutCheckbox.setDisable(true);
    }

    /**
     * Re-set the forced files in the update action. This needs to be
     * done when the "include layout" option is toggled.
     */
    private void resetForcedFiles()
    {
        Set<File> forcedFiles = new HashSet<>(forcedLayoutFiles);
        if (includeLayout) {
            forcedFiles.addAll(changedLayoutFiles.stream().map(TeamStatusInfo::getFile).collect(Collectors.toSet()));
        }
        updateAction.setFilesToForceUpdate(forcedFiles);
    }

    /**
     * Inner class to do the actual cvs status check to populate commit dialog
     * to ensure that the UI is not blocked during remote call
     */
    class UpdateWorker extends FXWorker implements StatusListener
    {
        List<TeamStatusInfo> response;
        TeamworkCommand command;
        TeamworkCommandResult result;
        private boolean aborted;
        private StatusHandle statusHandle;

        @OnThread(Tag.FXPlatform)
        public UpdateWorker()
        {
            super();
            response = new ArrayList<>();
            FileFilter filter = project.getTeamSettingsController().getFileFilter(true, !isDVCS);
            command = repository.getStatus(this, filter, true);
        }

        /* (non-Javadoc)
         * @see bluej.groupwork.StatusListener#gotStatus(bluej.groupwork.TeamStatusInfo)
         */
        @OnThread(Tag.Any)
        public void gotStatus(TeamStatusInfo info)
        {
            response.add(info);
        }

        /* (non-Javadoc)
         * @see bluej.groupwork.StatusListener#statusComplete(bluej.groupwork.CommitHandle)
         */
        @OnThread(Tag.Worker)
        public void statusComplete(StatusHandle statusHandle)
        {
            pullWithNoChanges = statusHandle.pullNeeded();
            this.statusHandle = statusHandle;
        }

        @OnThread(Tag.Worker)
        public Object construct()
        {
            result = command.getResult();
            return response;
        }

        public void abort()
        {
            command.cancel();
            aborted = true;
        }

        @OnThread(Tag.FXPlatform)
        public void finished()
        {
            stopProgress();
            if (! aborted) {
                if (result.isError()) {
                    UpdateFilesFrame.this.dialogThenHide(() -> TeamUtils.handleServerResponseFX(result, UpdateFilesFrame.this.asWindow()));
                }
                else {
                    Set<File> filesToUpdate = new HashSet<>();
                    Set<File> conflicts = new HashSet<>();
                    Set<File> modifiedLayoutFiles = new HashSet<>();

                    List<TeamStatusInfo> info = response;
                    getUpdateFileSet(info, filesToUpdate, conflicts, modifiedLayoutFiles);

                    if (conflicts.size() != 0) {
                        String filesList = "";
                        Iterator<File> i = conflicts.iterator();
                        for (int j = 0; j < 10 && i.hasNext(); j++) {
                            File conflictFile = i.next();
                            filesList += "    " + conflictFile.getName() + "\n";
                        }

                        // If there are more than 10 conflicts, we won't list them
                        // all in the dialog
                        if (i.hasNext()) {
                            filesList += "    (and more - check status)";
                        }

                        String filesListFinal = filesList;
                        UpdateFilesFrame.this.dialogThenHide(() -> DialogManager.showMessageWithTextFX(UpdateFilesFrame.this.asWindow(), "team-unresolved-conflicts", filesListFinal));
                        return;
                    }

                    // Build the actual set of files to update. If there are new or removed
                    // directories, don't include files within.
                    Set<File> updateFiles = new HashSet<>();
                    for (File file : filesToUpdate) {
                        if (!filesToUpdate.contains(file.getParentFile())) {
                            updateFiles.add(file);
                        }
                    }
                    forcedLayoutFiles.removeIf(file -> filesToUpdate.contains(file.getParentFile()));

                    updateAction.setStatusHandle(statusHandle);
                    updateAction.setFilesToUpdate(updateFiles);
                    resetForcedFiles();

                    if (includeLayout && ! changedLayoutFiles.isEmpty()) {
                        addModifiedLayouts();
                    }

                    if(updateListModel.isEmpty() && !pullWithNoChanges) {
                        updateListModel.add(noFilesToUpdate);
                    }
                    else {
                        if (isDVCS && pullWithNoChanges && updateListModel.isEmpty()) {
                            updateListModel.add(needUpdate);
                        }
                        updateAction.setEnabled(true);
                    }
                }
            }
        }

        /**
         * Go through the status list, and figure out which files to update, and
         * which to force update.
         *
         * @param info  The list of files with status (List of TeamStatusInfo)
         * @param filesToUpdate  The set to store the files to update in
         * @param modifiedLayoutFiles  The set to store the files to be force updated in
         * @param conflicts      The set to store unresolved conflicts in
         *                       (any files in this set prevent update from occurring)
         */
        private void getUpdateFileSet(List<TeamStatusInfo> info, Set<File> filesToUpdate, Set<File> conflicts, Set<File> modifiedLayoutFiles)
        {
            UpdateFilter filter = new UpdateFilter();
            TeamViewFilter viewFilter = new TeamViewFilter();
            for (TeamStatusInfo statusInfo : info) {
                //update must look in the remoteStatus in a DVCS. if not DVCS, look into the local status.
                Status status = statusInfo.getStatus(!isDVCS);
                if (filter.accept(statusInfo)) {
                    if (!BlueJPackageFile.isPackageFileName(statusInfo.getFile().getName())) {
                        updateListModel.add(new UpdateStatus(statusInfo));
                        filesToUpdate.add(statusInfo.getFile());
                    }
                    else {
                        if (!viewFilter.accept(statusInfo)) {
                            // If the file should not be viewed, just ignore it.
                        }
                        else if (filter.updateAlways(statusInfo)) {
                            // The package file is new or removed. There is no
                            // option not to include it in the update.
                            updateListModel.add(new UpdateStatus(statusInfo));
                            forcedLayoutFiles.add(statusInfo.getFile());
                        }
                        else {
                            // add file to list of files that may be added to commit
                            modifiedLayoutFiles.add(statusInfo.getFile());
                            // keep track of StatusInfo objects representing changed diagrams
                            changedLayoutFiles.add(statusInfo);
                        }
                    }
                }
                else {
                    boolean conflict;
                    conflict = status == Status.UNRESOLVED;
                    conflict |= status == Status.CONFLICT_ADD;
                    conflict |= status == Status.CONFLICT_LMRD;
                    if (conflict) {
                        if (!BlueJPackageFile.isPackageFileName(statusInfo.getFile().getName())) {
                            conflicts.add(statusInfo.getFile());
                        }
                        else {
                            // bluej package file will be force-updated
                            modifiedLayoutFiles.add(statusInfo.getFile());
                            changedLayoutFiles.add(statusInfo);
                        }
                    }
                }
            }

            if (! changedLayoutFiles.isEmpty()) {
                setLayoutChanged();
            }
        }
    }
}
