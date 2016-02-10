/*
 * Autopsy Forensic Browser
 *
 * Copyright 2013 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.report;

import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingWorker;
import org.openide.filesystems.FileUtil;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.coreutils.EscapeUtil;
import org.sleuthkit.autopsy.coreutils.ImageUtils;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.coreutils.MessageNotifyUtil;
import org.sleuthkit.autopsy.report.ReportProgressPanel.ReportStatus;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE;
import org.sleuthkit.datamodel.BlackboardArtifactTag;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.datamodel.ContentTag;
import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.SleuthkitCase.CaseDbQuery;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;
import org.sleuthkit.datamodel.BlackboardAttribute.Type;

/**
 * Instances of this class use GeneralReportModules, TableReportModules and
 * FileReportModules to generate a report. If desired, displayProgressPanels()
 * can be called to show report generation progress using ReportProgressPanel
 * objects displayed using a dialog box.
 */
class ReportGenerator {

    private static final Logger logger = Logger.getLogger(ReportGenerator.class.getName());

    private Case currentCase = Case.getCurrentCase();
    private SleuthkitCase skCase = currentCase.getSleuthkitCase();

    private Map<TableReportModule, ReportProgressPanel> tableProgress;
    private Map<GeneralReportModule, ReportProgressPanel> generalProgress;
    private Map<FileReportModule, ReportProgressPanel> fileProgress;

    private String reportPath;
    private ReportGenerationPanel panel = new ReportGenerationPanel();

    static final String REPORTS_DIR = "Reports"; //NON-NLS

    private List<String> errorList;

    /**
     * Displays the list of errors during report generation in user-friendly
     * way. MessageNotifyUtil used to display bubble notification.
     *
     * @param listOfErrors List of strings explaining the errors.
     */
    private void displayReportErrors() {
        if (!errorList.isEmpty()) {
            String errorString = "";
            for (String error : errorList) {
                errorString += error + "\n";
            }
            MessageNotifyUtil.Notify.error(
                    NbBundle.getMessage(this.getClass(), "ReportGenerator.notifyErr.errsDuringRptGen"), errorString);
            return;
        }
    }

    ReportGenerator(Map<TableReportModule, Boolean> tableModuleStates, Map<GeneralReportModule, Boolean> generalModuleStates, Map<FileReportModule, Boolean> fileListModuleStates) {
        // Create the root reports directory path of the form: <CASE DIRECTORY>/Reports/<Case fileName> <Timestamp>/
        DateFormat dateFormat = new SimpleDateFormat("MM-dd-yyyy-HH-mm-ss");
        Date date = new Date();
        String dateNoTime = dateFormat.format(date);
        this.reportPath = currentCase.getReportDirectory() + File.separator + currentCase.getName() + " " + dateNoTime + File.separator;

        this.errorList = new ArrayList<String>();

        // Create the root reports directory.
        try {
            FileUtil.createFolder(new File(this.reportPath));
        } catch (IOException ex) {
            errorList.add(NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedMakeRptFolder"));
            logger.log(Level.SEVERE, "Failed to make report folder, may be unable to generate reports.", ex); //NON-NLS
            return;
        }

        // Initialize the progress panels
        generalProgress = new HashMap<>();
        tableProgress = new HashMap<>();
        fileProgress = new HashMap<>();
        setupProgressPanels(tableModuleStates, generalModuleStates, fileListModuleStates);
    }

    /**
     * Create a ReportProgressPanel for each report generation module selected
     * by the user.
     *
     * @param tableModuleStates The enabled/disabled state of each
     * TableReportModule
     * @param generalModuleStates The enabled/disabled state of each
     * GeneralReportModule
     * @param fileListModuleStates The enabled/disabled state of each
     * FileReportModule
     */
    private void setupProgressPanels(Map<TableReportModule, Boolean> tableModuleStates, Map<GeneralReportModule, Boolean> generalModuleStates, Map<FileReportModule, Boolean> fileListModuleStates) {
        if (null != tableModuleStates) {
            for (Entry<TableReportModule, Boolean> entry : tableModuleStates.entrySet()) {
                if (entry.getValue()) {
                    TableReportModule module = entry.getKey();
                    String reportFilePath = module.getRelativeFilePath();
                    if (!reportFilePath.isEmpty()) {
                        tableProgress.put(module, panel.addReport(module.getName(), reportPath + reportFilePath));
                    } else {
                        tableProgress.put(module, panel.addReport(module.getName(), null));
                    }
                }
            }
        }

        if (null != generalModuleStates) {
            for (Entry<GeneralReportModule, Boolean> entry : generalModuleStates.entrySet()) {
                if (entry.getValue()) {
                    GeneralReportModule module = entry.getKey();
                    String reportFilePath = module.getRelativeFilePath();
                    if (!reportFilePath.isEmpty()) {
                        generalProgress.put(module, panel.addReport(module.getName(), reportPath + reportFilePath));
                    } else {
                        generalProgress.put(module, panel.addReport(module.getName(), null));
                    }
                }
            }
        }

        if (null != fileListModuleStates) {
            for (Entry<FileReportModule, Boolean> entry : fileListModuleStates.entrySet()) {
                if (entry.getValue()) {
                    FileReportModule module = entry.getKey();
                    String reportFilePath = module.getRelativeFilePath();
                    if (!reportFilePath.isEmpty()) {
                        fileProgress.put(module, panel.addReport(module.getName(), reportPath + reportFilePath));
                    } else {
                        fileProgress.put(module, panel.addReport(module.getName(), null));
                    }
                }
            }
        }
    }

    /**
     * Display the progress panels to the user, and add actions to close the
     * parent dialog.
     */
    public void displayProgressPanels() {
        final JDialog dialog = new JDialog(new JFrame(), true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setTitle(NbBundle.getMessage(this.getClass(), "ReportGenerator.displayProgress.title.text"));
        dialog.add(this.panel);
        dialog.pack();

        panel.addCloseAction(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });

        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                panel.close();
            }
        });

        Dimension screenDimension = Toolkit.getDefaultToolkit().getScreenSize();
        int w = dialog.getSize().width;
        int h = dialog.getSize().height;

        // set the location of the popUp Window on the center of the screen
        dialog.setLocation((screenDimension.width - w) / 2, (screenDimension.height - h) / 2);
        dialog.setVisible(true);
    }

    /**
     * Run the GeneralReportModules using a SwingWorker.
     */
    public void generateGeneralReports() {
        GeneralReportsWorker worker = new GeneralReportsWorker();
        worker.execute();
    }

    /**
     * Run the TableReportModules using a SwingWorker.
     *
     * @param artifactTypeSelections the enabled/disabled state of the artifact
     * types to be included in the report
     * @param tagSelections the enabled/disabled state of the tag names to be
     * included in the report
     */
    public void generateTableReports(Map<BlackboardArtifact.Type, Boolean> artifactTypeSelections, Map<String, Boolean> tagNameSelections) {
        if (!tableProgress.isEmpty() && null != artifactTypeSelections) {
            TableReportsWorker worker = new TableReportsWorker(artifactTypeSelections, tagNameSelections);
            worker.execute();
        }
    }

    /**
     * Run the FileReportModules using a SwingWorker.
     *
     * @param enabledInfo the Information that should be included about each
     * file in the report.
     */
    public void generateFileListReports(Map<FileReportDataTypes, Boolean> enabledInfo) {
        if (!fileProgress.isEmpty() && null != enabledInfo) {
            List<FileReportDataTypes> enabled = new ArrayList<>();
            for (Entry<FileReportDataTypes, Boolean> e : enabledInfo.entrySet()) {
                if (e.getValue()) {
                    enabled.add(e.getKey());
                }
            }
            FileReportsWorker worker = new FileReportsWorker(enabled);
            worker.execute();
        }
    }

    /**
     * SwingWorker to run GeneralReportModules.
     */
    private class GeneralReportsWorker extends SwingWorker<Integer, Integer> {

        @Override
        protected Integer doInBackground() throws Exception {
            for (Entry<GeneralReportModule, ReportProgressPanel> entry : generalProgress.entrySet()) {
                GeneralReportModule module = entry.getKey();
                if (generalProgress.get(module).getStatus() != ReportStatus.CANCELED) {
                    module.generateReport(reportPath, generalProgress.get(module));
                }
            }
            return 0;
        }

        @Override
        protected void done() {
            try {
                get();
            } catch (InterruptedException | ExecutionException ex) {
                MessageNotifyUtil.Notify.show(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.errors.reportErrorTitle"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.errors.reportErrorText") + ex.getLocalizedMessage(),
                        MessageNotifyUtil.MessageType.ERROR);
                logger.log(Level.SEVERE, "failed to generate reports", ex); //NON-NLS
            } // catch and ignore if we were cancelled
            catch (java.util.concurrent.CancellationException ex) {
            } finally {
                displayReportErrors();
                errorList.clear();
            }
        }

    }

    /**
     * SwingWorker to run FileReportModules.
     */
    private class FileReportsWorker extends SwingWorker<Integer, Integer> {

        private List<FileReportDataTypes> enabledInfo = Arrays.asList(FileReportDataTypes.values());
        private List<FileReportModule> fileModules = new ArrayList<>();

        FileReportsWorker(List<FileReportDataTypes> enabled) {
            enabledInfo = enabled;
            for (Entry<FileReportModule, ReportProgressPanel> entry : fileProgress.entrySet()) {
                fileModules.add(entry.getKey());
            }
        }

        @Override
        protected Integer doInBackground() throws Exception {
            for (FileReportModule module : fileModules) {
                ReportProgressPanel progress = fileProgress.get(module);
                if (progress.getStatus() != ReportStatus.CANCELED) {
                    progress.start();
                    progress.updateStatusLabel(
                            NbBundle.getMessage(this.getClass(), "ReportGenerator.progress.queryingDb.text"));
                }
            }

            List<AbstractFile> files = getFiles();
            int numFiles = files.size();
            for (FileReportModule module : fileModules) {
                module.startReport(reportPath);
                module.startTable(enabledInfo);
                fileProgress.get(module).setIndeterminate(false);
                fileProgress.get(module).setMaximumProgress(numFiles);
            }

            int i = 0;
            // Add files to report.
            for (AbstractFile file : files) {
                // Check to see if any reports have been cancelled.
                if (fileModules.isEmpty()) {
                    break;
                }
                // Remove cancelled reports, add files to report otherwise.
                Iterator<FileReportModule> iter = fileModules.iterator();
                while (iter.hasNext()) {
                    FileReportModule module = iter.next();
                    ReportProgressPanel progress = fileProgress.get(module);
                    if (progress.getStatus() == ReportStatus.CANCELED) {
                        iter.remove();
                    } else {
                        module.addRow(file, enabledInfo);
                        progress.increment();
                    }

                    if ((i % 100) == 0) {
                        progress.updateStatusLabel(
                                NbBundle.getMessage(this.getClass(), "ReportGenerator.progress.processingFile.text",
                                        file.getName()));
                    }
                }
                i++;
            }

            for (FileReportModule module : fileModules) {
                module.endTable();
                module.endReport();
                fileProgress.get(module).complete(ReportStatus.COMPLETE);
            }

            return 0;
        }

        /**
         * Get all files in the image.
         *
         * @return
         */
        private List<AbstractFile> getFiles() {
            List<AbstractFile> absFiles;
            try {
                SleuthkitCase skCase = Case.getCurrentCase().getSleuthkitCase();
                absFiles = skCase.findAllFilesWhere("meta_type != " + TskData.TSK_FS_META_TYPE_ENUM.TSK_FS_META_TYPE_DIR.getValue()); //NON-NLS
                return absFiles;
            } catch (TskCoreException ex) {
                MessageNotifyUtil.Notify.show(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.errors.reportErrorTitle"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.errors.reportErrorText") + ex.getLocalizedMessage(),
                        MessageNotifyUtil.MessageType.ERROR);
                logger.log(Level.SEVERE, "failed to generate reports. Unable to get all files in the image.", ex); //NON-NLS
                return Collections.<AbstractFile>emptyList();
            }
        }

        @Override
        protected void done() {
            try {
                get();
            } catch (InterruptedException | ExecutionException ex) {
                MessageNotifyUtil.Notify.show(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.errors.reportErrorTitle"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.errors.reportErrorText") + ex.getLocalizedMessage(),
                        MessageNotifyUtil.MessageType.ERROR);
                logger.log(Level.SEVERE, "failed to generate reports", ex); //NON-NLS
            } // catch and ignore if we were cancelled
            catch (java.util.concurrent.CancellationException ex) {
            } finally {
                displayReportErrors();
                errorList.clear();
            }
        }
    }

    /**
     * SwingWorker to run TableReportModules to report on blackboard artifacts,
     * content tags, and blackboard artifact tags.
     */
    private class TableReportsWorker extends SwingWorker<Integer, Integer> {

        private List<TableReportModule> tableModules = new ArrayList<>();
        private List<BlackboardArtifact.Type> artifactTypes = new ArrayList<>();
        private HashSet<String> tagNamesFilter = new HashSet<>();

        private List<Content> images = new ArrayList<>();

        TableReportsWorker(Map<BlackboardArtifact.Type, Boolean> artifactTypeSelections, Map<String, Boolean> tagNameSelections) {
            // Get the report modules selected by the user.
            for (Entry<TableReportModule, ReportProgressPanel> entry : tableProgress.entrySet()) {
                tableModules.add(entry.getKey());
            }

            // Get the artifact types selected by the user.
            for (Entry<BlackboardArtifact.Type, Boolean> entry : artifactTypeSelections.entrySet()) {
                if (entry.getValue()) {
                    artifactTypes.add(entry.getKey());
                }
            }

            // Get the tag names selected by the user and make a tag names filter.
            if (null != tagNameSelections) {
                for (Entry<String, Boolean> entry : tagNameSelections.entrySet()) {
                    if (entry.getValue() == true) {
                        tagNamesFilter.add(entry.getKey());
                    }
                }
            }
        }

        @Override
        protected Integer doInBackground() throws Exception {
            // Start the progress indicators for each active TableReportModule.
            for (TableReportModule module : tableModules) {
                ReportProgressPanel progress = tableProgress.get(module);
                if (progress.getStatus() != ReportStatus.CANCELED) {
                    module.startReport(reportPath);
                    progress.start();
                    progress.setIndeterminate(false);
                    progress.setMaximumProgress(ARTIFACT_TYPE.values().length + 2); // +2 for content and blackboard artifact tags
                }
            }

            // report on the blackboard results
            makeBlackboardArtifactTables();

            // report on the tagged files and artifacts
            makeContentTagsTables();
            makeBlackboardArtifactTagsTables();

            // report on the tagged images
            makeThumbnailTable();

            // finish progress, wrap up
            for (TableReportModule module : tableModules) {
                tableProgress.get(module).complete(ReportStatus.COMPLETE);
                module.endReport();
            }

            return 0;
        }

        /**
         * Generate the tables for the selected blackboard artifacts
         */
        private void makeBlackboardArtifactTables() {
            // Make a comment string describing the tag names filter in effect. 
            StringBuilder comment = new StringBuilder();
            if (!tagNamesFilter.isEmpty()) {
                comment.append(NbBundle.getMessage(this.getClass(), "ReportGenerator.artifactTable.taggedResults.text"));
                comment.append(makeCommaSeparatedList(tagNamesFilter));
            }

            // Add a table to the report for every enabled blackboard artifact type.
            for (BlackboardArtifact.Type type : artifactTypes) {
                // Check for cancellaton.
                removeCancelledTableReportModules();
                if (tableModules.isEmpty()) {
                    return;
                }

                for (TableReportModule module : tableModules) {
                    tableProgress.get(module).updateStatusLabel(
                            NbBundle.getMessage(this.getClass(), "ReportGenerator.progress.processing",
                                    type.getDisplayName()));
                }

                // Keyword hits and hashset hit artifacts get special handling.
                if (type.getTypeID() == ARTIFACT_TYPE.TSK_KEYWORD_HIT.getTypeID()) {
                    writeKeywordHits(tableModules, comment.toString(), tagNamesFilter);
                    continue;
                } else if (type.getTypeID() == ARTIFACT_TYPE.TSK_HASHSET_HIT.getTypeID()) {
                    writeHashsetHits(tableModules, comment.toString(), tagNamesFilter);
                    continue;
                }

                List<ArtifactData> artifactList = getFilteredArtifacts(type, tagNamesFilter);

                if (artifactList.isEmpty()) {
                    continue;
                }

                Set<BlackboardAttribute.Type> attrTypeSet = new TreeSet<>();
                for (ArtifactData data : artifactList) {
                    List<BlackboardAttribute> attributes = data.getAttributes();
                    for (BlackboardAttribute attribute : attributes) {
                        attrTypeSet.add(attribute.getAttributeType());
                    }
                }
                // Get the column headers appropriate for the artifact type.
                List<Cell> columnHeaders = getArtifactTableColumnHeaders(type.getTypeID(), attrTypeSet);
                for (ArtifactData d : artifactList) {
                    d.setColumnHeaders(columnHeaders);
                }
                // The most efficient way to sort all the Artifacts is to add them to a List, and then
                // sort that List based off a Comparator. Adding to a TreeMap/Set/List sorts the list
                // each time an element is added, which adds unnecessary overhead if we only need it sorted once.
                Collections.sort(artifactList);
                List<String> columnHeaderNames = new ArrayList<>();
                for (Cell c : columnHeaders) {
                    columnHeaderNames.add(c.getColumnHeader());
                }

                for (TableReportModule module : tableModules) {
                    module.startDataType(type.getDisplayName(), comment.toString());
                    module.startTable(columnHeaderNames);
                }
                for (ArtifactData artifactData : artifactList) {
                    // Add the row data to all of the reports.
                    for (TableReportModule module : tableModules) {

                        // Get the row data for this type of artifact.
                        List<String> rowData = artifactData.getRow();
                        if (rowData.isEmpty()) {
                            continue;
                        }

                        module.addRow(rowData);
                    }
                }
                // Finish up this data type
                for (TableReportModule module : tableModules) {
                    tableProgress.get(module).increment();
                    module.endTable();
                    module.endDataType();
                }
            }
        }

        /**
         * Make table for tagged files
         */
        @SuppressWarnings("deprecation")
        private void makeContentTagsTables() {
            // Check for cancellaton.
            removeCancelledTableReportModules();
            if (tableModules.isEmpty()) {
                return;
            }

            // Get the content tags.
            List<ContentTag> tags;
            try {
                tags = Case.getCurrentCase().getServices().getTagsManager().getAllContentTags();
            } catch (TskCoreException ex) {
                errorList.add(NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedGetContentTags"));
                logger.log(Level.SEVERE, "failed to get content tags", ex); //NON-NLS
                return;
            }

            // Tell the modules reporting on content tags is beginning.
            for (TableReportModule module : tableModules) {
                // @@@ This casting is a tricky little workaround to allow the HTML report module to slip in a content hyperlink.
                // @@@ Alos Using the obsolete ARTIFACT_TYPE.TSK_TAG_FILE is also an expedient hack.
                tableProgress.get(module).updateStatusLabel(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.progress.processing",
                                ARTIFACT_TYPE.TSK_TAG_FILE.getDisplayName()));
                ArrayList<String> columnHeaders = new ArrayList<>(Arrays.asList(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.htmlOutput.header.tag"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.htmlOutput.header.file"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.htmlOutput.header.comment"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.htmlOutput.header.timeModified"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.htmlOutput.header.timeChanged"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.htmlOutput.header.timeAccessed"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.htmlOutput.header.timeCreated"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.htmlOutput.header.size"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.htmlOutput.header.hash")));

                StringBuilder comment = new StringBuilder();
                if (!tagNamesFilter.isEmpty()) {
                    comment.append(
                            NbBundle.getMessage(this.getClass(), "ReportGenerator.makeContTagTab.taggedFiles.msg"));
                    comment.append(makeCommaSeparatedList(tagNamesFilter));
                }
                if (module instanceof ReportHTML) {
                    ReportHTML htmlReportModule = (ReportHTML) module;
                    htmlReportModule.startDataType(ARTIFACT_TYPE.TSK_TAG_FILE.getDisplayName(), comment.toString());
                    htmlReportModule.startContentTagsTable(columnHeaders);
                } else {
                    module.startDataType(ARTIFACT_TYPE.TSK_TAG_FILE.getDisplayName(), comment.toString());
                    module.startTable(columnHeaders);
                }
            }

            // Give the modules the rows for the content tags. 
            for (ContentTag tag : tags) {
                // skip tags that we are not reporting on 
                if (passesTagNamesFilter(tag.getName().getDisplayName()) == false) {
                    continue;
                }

                String fileName;
                try {
                    fileName = tag.getContent().getUniquePath();
                } catch (TskCoreException ex) {
                    fileName = tag.getContent().getName();
                }

                ArrayList<String> rowData = new ArrayList<>(Arrays.asList(tag.getName().getDisplayName(), fileName, tag.getComment()));
                for (TableReportModule module : tableModules) {
                    // @@@ This casting is a tricky little workaround to allow the HTML report module to slip in a content hyperlink.
                    if (module instanceof ReportHTML) {
                        ReportHTML htmlReportModule = (ReportHTML) module;
                        htmlReportModule.addRowWithTaggedContentHyperlink(rowData, tag);
                    } else {
                        module.addRow(rowData);
                    }
                }

                // see if it is for an image so that we later report on it
                checkIfTagHasImage(tag);
            }

            // The the modules content tags reporting is ended.
            for (TableReportModule module : tableModules) {
                tableProgress.get(module).increment();
                module.endTable();
                module.endDataType();
            }
        }

        @Override
        protected void done() {
            try {
                get();
            } catch (InterruptedException | ExecutionException ex) {
                MessageNotifyUtil.Notify.show(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.errors.reportErrorTitle"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.errors.reportErrorText") + ex.getLocalizedMessage(),
                        MessageNotifyUtil.MessageType.ERROR);
                logger.log(Level.SEVERE, "failed to generate reports", ex); //NON-NLS
            } // catch and ignore if we were cancelled
            catch (java.util.concurrent.CancellationException ex) {
            } finally {
                displayReportErrors();
                errorList.clear();
            }
        }

        /**
         * Generate the tables for the tagged artifacts
         */
        @SuppressWarnings("deprecation")
        private void makeBlackboardArtifactTagsTables() {
            // Check for cancellaton.
            removeCancelledTableReportModules();
            if (tableModules.isEmpty()) {
                return;
            }

            List<BlackboardArtifactTag> tags;
            try {
                tags = Case.getCurrentCase().getServices().getTagsManager().getAllBlackboardArtifactTags();
            } catch (TskCoreException ex) {
                errorList.add(NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedGetBBArtifactTags"));
                logger.log(Level.SEVERE, "failed to get blackboard artifact tags", ex); //NON-NLS
                return;
            }

            // Tell the modules reporting on blackboard artifact tags data type is beginning.
            // @@@ Using the obsolete ARTIFACT_TYPE.TSK_TAG_ARTIFACT is an expedient hack.
            for (TableReportModule module : tableModules) {
                tableProgress.get(module).updateStatusLabel(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.progress.processing",
                                ARTIFACT_TYPE.TSK_TAG_ARTIFACT.getDisplayName()));
                StringBuilder comment = new StringBuilder();
                if (!tagNamesFilter.isEmpty()) {
                    comment.append(
                            NbBundle.getMessage(this.getClass(), "ReportGenerator.makeBbArtTagTab.taggedRes.msg"));
                    comment.append(makeCommaSeparatedList(tagNamesFilter));
                }
                module.startDataType(ARTIFACT_TYPE.TSK_TAG_ARTIFACT.getDisplayName(), comment.toString());
                module.startTable(new ArrayList<>(Arrays.asList(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.tagTable.header.resultType"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.tagTable.header.tag"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.tagTable.header.comment"),
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.tagTable.header.srcFile"))));
            }

            // Give the modules the rows for the content tags. 
            for (BlackboardArtifactTag tag : tags) {
                if (passesTagNamesFilter(tag.getName().getDisplayName()) == false) {
                    continue;
                }

                List<String> row;
                for (TableReportModule module : tableModules) {
                    row = new ArrayList<>(Arrays.asList(tag.getArtifact().getArtifactTypeName(), tag.getName().getDisplayName(), tag.getComment(), tag.getContent().getName()));
                    module.addRow(row);
                }

                // check if the tag is an image that we should later make a thumbnail for
                checkIfTagHasImage(tag);
            }

            // The the modules blackboard artifact tags reporting is ended.
            for (TableReportModule module : tableModules) {
                tableProgress.get(module).increment();
                module.endTable();
                module.endDataType();
            }
        }

        /**
         * Test if the user requested that this tag be reported on
         *
         * @param tagName
         *
         * @return true if it should be reported on
         */
        private boolean passesTagNamesFilter(String tagName) {
            return tagNamesFilter.isEmpty() || tagNamesFilter.contains(tagName);
        }

        void removeCancelledTableReportModules() {
            Iterator<TableReportModule> iter = tableModules.iterator();
            while (iter.hasNext()) {
                TableReportModule module = iter.next();
                if (tableProgress.get(module).getStatus() == ReportStatus.CANCELED) {
                    iter.remove();
                }
            }
        }

        /**
         * Make a report for the files that were previously found to be images.
         */
        private void makeThumbnailTable() {
            for (TableReportModule module : tableModules) {
                tableProgress.get(module).updateStatusLabel(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.progress.createdThumb.text"));

                if (module instanceof ReportHTML) {
                    ReportHTML htmlModule = (ReportHTML) module;
                    htmlModule.startDataType(
                            NbBundle.getMessage(this.getClass(), "ReportGenerator.thumbnailTable.name"),
                            NbBundle.getMessage(this.getClass(), "ReportGenerator.thumbnailTable.desc"));
                    List<String> emptyHeaders = new ArrayList<>();
                    for (int i = 0; i < ReportHTML.THUMBNAIL_COLUMNS; i++) {
                        emptyHeaders.add("");
                    }
                    htmlModule.startTable(emptyHeaders);

                    htmlModule.addThumbnailRows(images);

                    htmlModule.endTable();
                    htmlModule.endDataType();
                }
            }
        }

        /**
         * Analyze artifact associated with tag and add to internal list if it
         * is associated with an image.
         *
         * @param artifactTag
         */
        private void checkIfTagHasImage(BlackboardArtifactTag artifactTag) {
            AbstractFile file;
            try {
                file = Case.getCurrentCase().getSleuthkitCase().getAbstractFileById(artifactTag.getArtifact().getObjectID());
            } catch (TskCoreException ex) {
                errorList.add(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.errGetContentFromBBArtifact"));
                logger.log(Level.WARNING, "Error while getting content from a blackboard artifact to report on.", ex); //NON-NLS
                return;
            }

            if (file != null) {
                checkIfFileIsImage(file);
            }
        }

        /**
         * Analyze file that tag is associated with and determine if it is an
         * image and should have a thumbnail reported for it. Images are added
         * to internal list.
         *
         * @param contentTag
         */
        private void checkIfTagHasImage(ContentTag contentTag) {
            Content c = contentTag.getContent();
            if (c instanceof AbstractFile == false) {
                return;
            }
            checkIfFileIsImage((AbstractFile) c);
        }

        /**
         * If file is an image file, add it to the internal 'images' list.
         *
         * @param file
         */
        private void checkIfFileIsImage(AbstractFile file) {

            if (file.isDir()
                    || file.getType() == TskData.TSK_DB_FILES_TYPE_ENUM.UNALLOC_BLOCKS
                    || file.getType() == TskData.TSK_DB_FILES_TYPE_ENUM.UNUSED_BLOCKS) {
                return;
            }

            if (ImageUtils.thumbnailSupported(file)) {
                images.add(file);
            }
        }
    }

    /// @@@ Should move the methods specific to TableReportsWorker into that scope.
    private Boolean failsTagFilter(HashSet<String> tagNames, HashSet<String> tagsNamesFilter) {
        if (null == tagsNamesFilter || tagsNamesFilter.isEmpty()) {
            return false;
        }

        HashSet<String> filteredTagNames = new HashSet<>(tagNames);
        filteredTagNames.retainAll(tagsNamesFilter);
        return filteredTagNames.isEmpty();
    }

    /**
     * Get a List of the artifacts and data of the given type that pass the
     * given Tag Filter.
     *
     * @param type The artifact type to get
     * @param tagNamesFilter The tag names that should be included.
     *
     * @return a list of the filtered tags.
     */
    private List<ArtifactData> getFilteredArtifacts(BlackboardArtifact.Type type, HashSet<String> tagNamesFilter) {
        List<ArtifactData> artifacts = new ArrayList<>();
        try {
            for (BlackboardArtifact artifact : skCase.getBlackboardArtifacts(type.getTypeID())) {
                List<BlackboardArtifactTag> tags = Case.getCurrentCase().getServices().getTagsManager().getBlackboardArtifactTagsByArtifact(artifact);
                HashSet<String> uniqueTagNames = new HashSet<>();
                for (BlackboardArtifactTag tag : tags) {
                    uniqueTagNames.add(tag.getName().getDisplayName());
                }
                if (failsTagFilter(uniqueTagNames, tagNamesFilter)) {
                    continue;
                }
                try {
                    artifacts.add(new ArtifactData(artifact, skCase.getBlackboardAttributes(artifact), uniqueTagNames));
                } catch (TskCoreException ex) {
                    errorList.add(NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedGetBBAttribs"));
                    logger.log(Level.SEVERE, "Failed to get Blackboard Attributes when generating report.", ex); //NON-NLS
                }
            }
        } catch (TskCoreException ex) {
            errorList.add(NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedGetBBArtifacts"));
            logger.log(Level.SEVERE, "Failed to get Blackboard Artifacts when generating report.", ex); //NON-NLS
        }
        return artifacts;
    }

    /**
     * Write the keyword hits to the provided TableReportModules.
     *
     * @param tableModules modules to report on
     */
    @SuppressWarnings("deprecation")
    private void writeKeywordHits(List<TableReportModule> tableModules, String comment, HashSet<String> tagNamesFilter) {

        // Query for keyword lists-only so that we can tell modules what lists
        // will exist for their index.
        // @@@ There is a bug in here.  We should use the tags in the below code
        // so that we only report the lists that we will later provide with real
        // hits.  If no keyord hits are tagged, then we make the page for nothing.
        String orderByClause;
        if (currentCase.getCaseType() == Case.CaseType.MULTI_USER_CASE) {
            orderByClause = "ORDER BY convert_to(att.value_text, 'SQL_ASCII') ASC NULLS FIRST"; //NON-NLS
        } else {
            orderByClause = "ORDER BY list ASC"; //NON-NLS
        }
        String keywordListQuery
                = "SELECT att.value_text AS list " + //NON-NLS
                "FROM blackboard_attributes AS att, blackboard_artifacts AS art " + //NON-NLS
                "WHERE att.attribute_type_id = " + ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID() + " " + //NON-NLS
                "AND art.artifact_type_id = " + ARTIFACT_TYPE.TSK_KEYWORD_HIT.getTypeID() + " " + //NON-NLS
                "AND att.artifact_id = art.artifact_id " + //NON-NLS
                "GROUP BY list " + orderByClause; //NON-NLS

        try (CaseDbQuery dbQuery = skCase.executeQuery(keywordListQuery)) {
            ResultSet listsRs = dbQuery.getResultSet();
            List<String> lists = new ArrayList<>();
            while (listsRs.next()) {
                String list = listsRs.getString("list"); //NON-NLS
                if (list.isEmpty()) {
                    list = NbBundle.getMessage(this.getClass(), "ReportGenerator.writeKwHits.userSrchs");
                }
                lists.add(list);
            }

            // Make keyword data type and give them set index
            for (TableReportModule module : tableModules) {
                module.startDataType(ARTIFACT_TYPE.TSK_KEYWORD_HIT.getDisplayName(), comment);
                module.addSetIndex(lists);
                tableProgress.get(module).updateStatusLabel(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.progress.processing",
                                ARTIFACT_TYPE.TSK_KEYWORD_HIT.getDisplayName()));
            }
        } catch (TskCoreException | SQLException ex) {
            errorList.add(NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedQueryKWLists"));
            logger.log(Level.SEVERE, "Failed to query keyword lists: ", ex); //NON-NLS
            return;
        }

        if (currentCase.getCaseType() == Case.CaseType.MULTI_USER_CASE) {
            orderByClause = "ORDER BY convert_to(att3.value_text, 'SQL_ASCII') ASC NULLS FIRST, " //NON-NLS
                    + "convert_to(att1.value_text, 'SQL_ASCII') ASC NULLS FIRST, " //NON-NLS
                    + "convert_to(f.parent_path, 'SQL_ASCII') ASC NULLS FIRST, " //NON-NLS
                    + "convert_to(f.name, 'SQL_ASCII') ASC NULLS FIRST, " //NON-NLS
                    + "convert_to(att2.value_text, 'SQL_ASCII') ASC NULLS FIRST"; //NON-NLS
        } else {
            orderByClause = "ORDER BY list ASC, keyword ASC, parent_path ASC, name ASC, preview ASC"; //NON-NLS
        }
        // Query for keywords, grouped by list
        String keywordsQuery
                = "SELECT art.artifact_id, art.obj_id, att1.value_text AS keyword, att2.value_text AS preview, att3.value_text AS list, f.name AS name, f.parent_path AS parent_path " + //NON-NLS
                "FROM blackboard_artifacts AS art, blackboard_attributes AS att1, blackboard_attributes AS att2, blackboard_attributes AS att3, tsk_files AS f " + //NON-NLS
                "WHERE (att1.artifact_id = art.artifact_id) " + //NON-NLS
                "AND (att2.artifact_id = art.artifact_id) " + //NON-NLS
                "AND (att3.artifact_id = art.artifact_id) " + //NON-NLS
                "AND (f.obj_id = art.obj_id) " + //NON-NLS
                "AND (att1.attribute_type_id = " + ATTRIBUTE_TYPE.TSK_KEYWORD.getTypeID() + ") " + //NON-NLS
                "AND (att2.attribute_type_id = " + ATTRIBUTE_TYPE.TSK_KEYWORD_PREVIEW.getTypeID() + ") " + //NON-NLS
                "AND (att3.attribute_type_id = " + ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID() + ") " + //NON-NLS
                "AND (art.artifact_type_id = " + ARTIFACT_TYPE.TSK_KEYWORD_HIT.getTypeID() + ") " + //NON-NLS
                orderByClause; //NON-NLS

        try (CaseDbQuery dbQuery = skCase.executeQuery(keywordsQuery)) {
            ResultSet resultSet = dbQuery.getResultSet();

            String currentKeyword = "";
            String currentList = "";
            while (resultSet.next()) {
                // Check to see if all the TableReportModules have been canceled
                if (tableModules.isEmpty()) {
                    break;
                }
                Iterator<TableReportModule> iter = tableModules.iterator();
                while (iter.hasNext()) {
                    TableReportModule module = iter.next();
                    if (tableProgress.get(module).getStatus() == ReportStatus.CANCELED) {
                        iter.remove();
                    }
                }

                // Get any tags that associated with this artifact and apply the tag filter.
                HashSet<String> uniqueTagNames = getUniqueTagNames(resultSet.getLong("artifact_id")); //NON-NLS
                if (failsTagFilter(uniqueTagNames, tagNamesFilter)) {
                    continue;
                }
                String tagsList = makeCommaSeparatedList(uniqueTagNames);

                Long objId = resultSet.getLong("obj_id"); //NON-NLS
                String keyword = resultSet.getString("keyword"); //NON-NLS
                String preview = resultSet.getString("preview"); //NON-NLS
                String list = resultSet.getString("list"); //NON-NLS
                String uniquePath = "";

                try {
                    AbstractFile f = skCase.getAbstractFileById(objId);
                    if (f != null) {
                        uniquePath = skCase.getAbstractFileById(objId).getUniquePath();
                    }
                } catch (TskCoreException ex) {
                    errorList.add(
                            NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedGetAbstractFileByID"));
                    logger.log(Level.WARNING, "Failed to get Abstract File by ID.", ex); //NON-NLS
                }

                // If the lists aren't the same, we've started a new list
                if ((!list.equals(currentList) && !list.isEmpty()) || (list.isEmpty() && !currentList.equals(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.writeKwHits.userSrchs")))) {
                    if (!currentList.isEmpty()) {
                        for (TableReportModule module : tableModules) {
                            module.endTable();
                            module.endSet();
                        }
                    }
                    currentList = list.isEmpty() ? NbBundle
                            .getMessage(this.getClass(), "ReportGenerator.writeKwHits.userSrchs") : list;
                    currentKeyword = ""; // reset the current keyword because it's a new list
                    for (TableReportModule module : tableModules) {
                        module.startSet(currentList);
                        tableProgress.get(module).updateStatusLabel(
                                NbBundle.getMessage(this.getClass(), "ReportGenerator.progress.processingList",
                                        ARTIFACT_TYPE.TSK_KEYWORD_HIT.getDisplayName(), currentList));
                    }
                }
                if (!keyword.equals(currentKeyword)) {
                    if (!currentKeyword.equals("")) {
                        for (TableReportModule module : tableModules) {
                            module.endTable();
                        }
                    }
                    currentKeyword = keyword;
                    for (TableReportModule module : tableModules) {
                        module.addSetElement(currentKeyword);
                        List<Cell> columnHeaders = getArtifactTableColumnHeaders(ARTIFACT_TYPE.TSK_KEYWORD_HIT.getTypeID(), new HashSet<BlackboardAttribute.Type>());
                        List<String> columnHeaderNames = new ArrayList<>();
                        for (Cell c : columnHeaders) {
                            columnHeaderNames.add(c.getColumnHeader());
                        }
                        module.startTable(columnHeaderNames);
                    }
                }

                String previewreplace = EscapeUtil.escapeHtml(preview);
                for (TableReportModule module : tableModules) {
                    module.addRow(Arrays.asList(new String[]{previewreplace.replaceAll("<!", ""), uniquePath, tagsList}));
                }
            }

            // Finish the current data type
            for (TableReportModule module : tableModules) {
                tableProgress.get(module).increment();
                module.endDataType();
            }
        } catch (TskCoreException | SQLException ex) {
            errorList.add(NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedQueryKWs"));
            logger.log(Level.SEVERE, "Failed to query keywords: ", ex); //NON-NLS
        }
    }

    /**
     * Write the hash set hits to the provided TableReportModules.
     *
     * @param tableModules modules to report on
     */
    @SuppressWarnings("deprecation")
    private void writeHashsetHits(List<TableReportModule> tableModules, String comment, HashSet<String> tagNamesFilter) {
        String orderByClause;
        if (currentCase.getCaseType() == Case.CaseType.MULTI_USER_CASE) {
            orderByClause = "ORDER BY convert_to(att.value_text, 'SQL_ASCII') ASC NULLS FIRST"; //NON-NLS
        } else {
            orderByClause = "ORDER BY att.value_text ASC"; //NON-NLS
        }
        String hashsetsQuery
                = "SELECT att.value_text AS list " + //NON-NLS
                "FROM blackboard_attributes AS att, blackboard_artifacts AS art " + //NON-NLS
                "WHERE att.attribute_type_id = " + ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID() + " " + //NON-NLS
                "AND art.artifact_type_id = " + ARTIFACT_TYPE.TSK_HASHSET_HIT.getTypeID() + " " + //NON-NLS
                "AND att.artifact_id = art.artifact_id " + //NON-NLS
                "GROUP BY list " + orderByClause; //NON-NLS

        try (CaseDbQuery dbQuery = skCase.executeQuery(hashsetsQuery)) {
            // Query for hashsets
            ResultSet listsRs = dbQuery.getResultSet();
            List<String> lists = new ArrayList<>();
            while (listsRs.next()) {
                lists.add(listsRs.getString("list")); //NON-NLS
            }

            for (TableReportModule module : tableModules) {
                module.startDataType(ARTIFACT_TYPE.TSK_HASHSET_HIT.getDisplayName(), comment);
                module.addSetIndex(lists);
                tableProgress.get(module).updateStatusLabel(
                        NbBundle.getMessage(this.getClass(), "ReportGenerator.progress.processing",
                                ARTIFACT_TYPE.TSK_HASHSET_HIT.getDisplayName()));
            }
        } catch (TskCoreException | SQLException ex) {
            errorList.add(NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedQueryHashsetLists"));
            logger.log(Level.SEVERE, "Failed to query hashset lists: ", ex); //NON-NLS
            return;
        }

        if (currentCase.getCaseType() == Case.CaseType.MULTI_USER_CASE) {
            orderByClause = "ORDER BY convert_to(att.value_text, 'SQL_ASCII') ASC NULLS FIRST, " //NON-NLS
                    + "convert_to(f.parent_path, 'SQL_ASCII') ASC NULLS FIRST, " //NON-NLS
                    + "convert_to(f.name, 'SQL_ASCII') ASC NULLS FIRST, " //NON-NLS
                    + "size ASC NULLS FIRST"; //NON-NLS
        } else {
            orderByClause = "ORDER BY att.value_text ASC, f.parent_path ASC, f.name ASC, size ASC"; //NON-NLS
        }
        String hashsetHitsQuery
                = "SELECT art.artifact_id, art.obj_id, att.value_text AS setname, f.name AS name, f.size AS size, f.parent_path AS parent_path " + //NON-NLS
                "FROM blackboard_artifacts AS art, blackboard_attributes AS att, tsk_files AS f " + //NON-NLS
                "WHERE (att.artifact_id = art.artifact_id) " + //NON-NLS
                "AND (f.obj_id = art.obj_id) " + //NON-NLS
                "AND (att.attribute_type_id = " + ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID() + ") " + //NON-NLS
                "AND (art.artifact_type_id = " + ARTIFACT_TYPE.TSK_HASHSET_HIT.getTypeID() + ") " + //NON-NLS
                orderByClause; //NON-NLS

        try (CaseDbQuery dbQuery = skCase.executeQuery(hashsetHitsQuery)) {
            // Query for hashset hits
            ResultSet resultSet = dbQuery.getResultSet();
            String currentSet = "";
            while (resultSet.next()) {
                // Check to see if all the TableReportModules have been canceled
                if (tableModules.isEmpty()) {
                    break;
                }
                Iterator<TableReportModule> iter = tableModules.iterator();
                while (iter.hasNext()) {
                    TableReportModule module = iter.next();
                    if (tableProgress.get(module).getStatus() == ReportStatus.CANCELED) {
                        iter.remove();
                    }
                }

                // Get any tags that associated with this artifact and apply the tag filter.
                HashSet<String> uniqueTagNames = getUniqueTagNames(resultSet.getLong("artifact_id")); //NON-NLS
                if (failsTagFilter(uniqueTagNames, tagNamesFilter)) {
                    continue;
                }
                String tagsList = makeCommaSeparatedList(uniqueTagNames);

                Long objId = resultSet.getLong("obj_id"); //NON-NLS
                String set = resultSet.getString("setname"); //NON-NLS
                String size = resultSet.getString("size"); //NON-NLS
                String uniquePath = "";

                try {
                    AbstractFile f = skCase.getAbstractFileById(objId);
                    if (f != null) {
                        uniquePath = skCase.getAbstractFileById(objId).getUniquePath();
                    }
                } catch (TskCoreException ex) {
                    errorList.add(
                            NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedGetAbstractFileFromID"));
                    logger.log(Level.WARNING, "Failed to get Abstract File from ID.", ex); //NON-NLS
                    return;
                }

                // If the sets aren't the same, we've started a new set
                if (!set.equals(currentSet)) {
                    if (!currentSet.isEmpty()) {
                        for (TableReportModule module : tableModules) {
                            module.endTable();
                            module.endSet();
                        }
                    }
                    currentSet = set;
                    for (TableReportModule module : tableModules) {
                        module.startSet(currentSet);
                        List<Cell> columnHeaders = getArtifactTableColumnHeaders(ARTIFACT_TYPE.TSK_HASHSET_HIT.getTypeID(), new HashSet<BlackboardAttribute.Type>());
                        List<String> columnHeaderNames = new ArrayList<>();
                        for (Cell c : columnHeaders) {
                            columnHeaderNames.add(c.getColumnHeader());
                        }
                        module.startTable(columnHeaderNames);
                        tableProgress.get(module).updateStatusLabel(
                                NbBundle.getMessage(this.getClass(), "ReportGenerator.progress.processingList",
                                        ARTIFACT_TYPE.TSK_HASHSET_HIT.getDisplayName(), currentSet));
                    }
                }

                // Add a row for this hit to every module
                for (TableReportModule module : tableModules) {
                    module.addRow(Arrays.asList(new String[]{uniquePath, size, tagsList}));
                }
            }

            // Finish the current data type
            for (TableReportModule module : tableModules) {
                tableProgress.get(module).increment();
                module.endDataType();
            }
        } catch (TskCoreException | SQLException ex) {
            errorList.add(NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedQueryHashsetHits"));
            logger.log(Level.SEVERE, "Failed to query hashsets hits: ", ex); //NON-NLS
        }
    }

    /**
     * For a given artifact type ID, return the list of the row titles we're
     * reporting on.
     *
     * @param artifactTypeId artifact type ID
     * @param types The set of types available for this artifact type
     *
     * @return List<String> row titles
     */
    private List<Cell> getArtifactTableColumnHeaders(int artifactTypeId, Set<BlackboardAttribute.Type> types) {
        ArrayList<Cell> columnHeaders = new ArrayList<>();
        if (ARTIFACT_TYPE.TSK_WEB_BOOKMARK.getTypeID() == artifactTypeId) {
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.url"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_URL.getTypeID(),
                ATTRIBUTE_TYPE.TSK_URL.getLabel(),
                ATTRIBUTE_TYPE.TSK_URL.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_URL.getValueType())),
                //ATTRIBUTE_TYPE.TSK_TITLE
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.title"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_TITLE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_TITLE.getLabel(),
                ATTRIBUTE_TYPE.TSK_TITLE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_TITLE.getValueType())),
                //ATTRIBUTE_TYPE.TSK_DATETIME_CREATED
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateCreated"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getValueType())),
                //ATTRIBUTE_TYPE.TSK_PROG_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.program"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_WEB_COOKIE.getTypeID() == artifactTypeId) {
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                //ATTRIBUTE_TYPE.TSK_URL
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.url"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_URL.getTypeID(),
                ATTRIBUTE_TYPE.TSK_URL.getLabel(),
                ATTRIBUTE_TYPE.TSK_URL.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_URL.getValueType())),
                //ATTRIBUTE_TYPE.TSK_DATETIME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                //ATTRIBUTE_TYPE.TSK_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.name"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_NAME.getValueType())),
                //ATTRIBUTE_TYPE.TSK_VALUE
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.value"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_VALUE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_VALUE.getLabel(),
                ATTRIBUTE_TYPE.TSK_VALUE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_VALUE.getValueType())),
                //ATTRIBUTE_TYPE.TSK_PROG_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.program"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_WEB_HISTORY.getTypeID() == artifactTypeId) {
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                //ATTRIBUTE_TYPE.TSK_URL
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.url"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_URL.getTypeID(),
                ATTRIBUTE_TYPE.TSK_URL.getLabel(),
                ATTRIBUTE_TYPE.TSK_URL.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_URL.getValueType())),
                //ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateAccessed"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getValueType())),
                //ATTRIBUTE_TYPE.TSK_REFERRER
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.referrer"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_REFERRER.getTypeID(),
                ATTRIBUTE_TYPE.TSK_REFERRER.getLabel(),
                ATTRIBUTE_TYPE.TSK_REFERRER.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_REFERRER.getValueType())),
                //ATTRIBUTE_TYPE.TSK_TITLE
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.title"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_TITLE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_TITLE.getLabel(),
                ATTRIBUTE_TYPE.TSK_TITLE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_TITLE.getValueType())),
                //ATTRIBUTE_TYPE.TSK_PROG_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.program"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType())),
                //ATTRIBUTE_TYPE.TSK_URL_DECODED
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.urlDomainDecoded"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_URL_DECODED.getTypeID(),
                ATTRIBUTE_TYPE.TSK_URL_DECODED.getLabel(),
                ATTRIBUTE_TYPE.TSK_URL_DECODED.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_URL_DECODED.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_WEB_DOWNLOAD.getTypeID() == artifactTypeId) {
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                //ATTRIBUTE_TYPE.TSK_PATH
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dest"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PATH.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PATH.getLabel(),
                ATTRIBUTE_TYPE.TSK_PATH.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PATH.getValueType())),
                //ATTRIBUTE_TYPE.TSK_URL
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.sourceUrl"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_URL.getTypeID(),
                ATTRIBUTE_TYPE.TSK_URL.getLabel(),
                ATTRIBUTE_TYPE.TSK_URL.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_URL.getValueType())),
                //ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateAccessed"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getValueType())),
                //ATTRIBUTE_TYPE.TSK_PROG_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.program"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_RECENT_OBJECT.getTypeID() == artifactTypeId) {
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                //ATTRIBUTE_TYPE.TSK_PATH
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.path"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PATH.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PATH.getLabel(),
                ATTRIBUTE_TYPE.TSK_PATH.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PATH.getValueType())),
                //ATTRIBUTE_TYPE.TSK_DATETIME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_INSTALLED_PROG.getTypeID() == artifactTypeId) {
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                //ATTRIBUTE_TYPE.TSK_PROG_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.progName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType())),
                //ATTRIBUTE_TYPE.TSK_DATETIME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.instDateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_KEYWORD_HIT.getTypeID() == artifactTypeId) {
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.preview")),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_HASHSET_HIT.getTypeID() == artifactTypeId) {
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.file")),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.size"))}));
        } else if (ARTIFACT_TYPE.TSK_DEVICE_ATTACHED.getTypeID() == artifactTypeId) {
            /*
             ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_ID.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.devMake"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getLabel(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.devModel"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getLabel(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.deviceId"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DEVICE_ID.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DEVICE_ID.getLabel(),
                ATTRIBUTE_TYPE.TSK_DEVICE_ID.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DEVICE_ID.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_WEB_SEARCH_QUERY.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_TEXT.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DOMAIN.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.text"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_TEXT.getTypeID(),
                ATTRIBUTE_TYPE.TSK_TEXT.getLabel(),
                ATTRIBUTE_TYPE.TSK_TEXT.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_TEXT.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.domain"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DOMAIN.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DOMAIN.getLabel(),
                ATTRIBUTE_TYPE.TSK_DOMAIN.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DOMAIN.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateAccessed"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.progName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_METADATA_EXIF.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTaken"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.devManufacturer"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getLabel(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.devModel"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getLabel(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.latitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.longitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.altitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_CONTACT.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_HOME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_OFFICE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_MOBILE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.personName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_NAME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.phoneNumber"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getLabel(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.phoneNumHome"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_HOME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_HOME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_HOME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_HOME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.phoneNumOffice"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_OFFICE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_OFFICE.getLabel(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_OFFICE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_OFFICE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.phoneNumMobile"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_MOBILE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_MOBILE.getLabel(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_MOBILE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_MOBILE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.email"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_EMAIL.getTypeID(),
                ATTRIBUTE_TYPE.TSK_EMAIL.getLabel(),
                ATTRIBUTE_TYPE.TSK_EMAIL.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_EMAIL.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_MESSAGE.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_MESSAGE_TYPE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DIRECTION.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_READ_STATUS.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_TO.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SUBJECT.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_TEXT.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.msgType"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_MESSAGE_TYPE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_MESSAGE_TYPE.getLabel(),
                ATTRIBUTE_TYPE.TSK_MESSAGE_TYPE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_MESSAGE_TYPE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.direction"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DIRECTION.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DIRECTION.getLabel(),
                ATTRIBUTE_TYPE.TSK_DIRECTION.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DIRECTION.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.readStatus"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_READ_STATUS.getTypeID(),
                ATTRIBUTE_TYPE.TSK_READ_STATUS.getLabel(),
                ATTRIBUTE_TYPE.TSK_READ_STATUS.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_READ_STATUS.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.fromPhoneNum"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getLabel(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.fromEmail"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getTypeID(),
                ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getLabel(),
                ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.toPhoneNum"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getLabel(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.toEmail"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_EMAIL_TO.getTypeID(),
                ATTRIBUTE_TYPE.TSK_EMAIL_TO.getLabel(),
                ATTRIBUTE_TYPE.TSK_EMAIL_TO.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_EMAIL_TO.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.subject"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_SUBJECT.getTypeID(),
                ATTRIBUTE_TYPE.TSK_SUBJECT.getLabel(),
                ATTRIBUTE_TYPE.TSK_SUBJECT.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_SUBJECT.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.text"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_TEXT.getTypeID(),
                ATTRIBUTE_TYPE.TSK_TEXT.getLabel(),
                ATTRIBUTE_TYPE.TSK_TEXT.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_TEXT.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_CALLLOG.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_START.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DIRECTION.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.personName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_NAME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.fromPhoneNum"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getLabel(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.toPhoneNum"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getLabel(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME_START.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME_START.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME_START.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME_START.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.direction"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DIRECTION.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DIRECTION.getLabel(),
                ATTRIBUTE_TYPE.TSK_DIRECTION.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DIRECTION.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_CALENDAR_ENTRY.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_CALENDAR_ENTRY_TYPE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DESCRIPTION.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_START.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_END.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.calendarEntryType"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_CALENDAR_ENTRY_TYPE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_CALENDAR_ENTRY_TYPE.getLabel(),
                ATTRIBUTE_TYPE.TSK_CALENDAR_ENTRY_TYPE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_CALENDAR_ENTRY_TYPE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.description"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DESCRIPTION.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DESCRIPTION.getLabel(),
                ATTRIBUTE_TYPE.TSK_DESCRIPTION.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DESCRIPTION.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.startDateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME_START.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME_START.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME_START.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME_START.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.endDateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME_END.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME_END.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME_END.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME_END.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.location"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getLabel(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_SPEED_DIAL_ENTRY.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SHORTCUT.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME_PERSON.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.shortCut"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_SHORTCUT.getTypeID(),
                ATTRIBUTE_TYPE.TSK_SHORTCUT.getLabel(),
                ATTRIBUTE_TYPE.TSK_SHORTCUT.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_SHORTCUT.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.personName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_NAME_PERSON.getTypeID(),
                ATTRIBUTE_TYPE.TSK_NAME_PERSON.getLabel(),
                ATTRIBUTE_TYPE.TSK_NAME_PERSON.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_NAME_PERSON.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.phoneNumber"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getLabel(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_BLUETOOTH_PAIRING.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_ID.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.deviceName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DEVICE_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DEVICE_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DEVICE_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DEVICE_NAME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.deviceAddress"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DEVICE_ID.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DEVICE_ID.getLabel(),
                ATTRIBUTE_TYPE.TSK_DEVICE_ID.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DEVICE_ID.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_GPS_TRACKPOINT.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.latitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.longitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_GPS_BOOKMARK.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.latitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.longitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.altitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.name"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_NAME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.locationAddress"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getLabel(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_GPS_LAST_KNOWN_LOCATION.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.latitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.longitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.altitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.name"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_NAME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.locationAddress"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getLabel(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_GPS_SEARCH.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.latitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.longitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.altitude"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.name"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_NAME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.locationAddress"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getLabel(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_SERVICE_ACCOUNT.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_CATEGORY.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_USER_ID.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PASSWORD.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_URL.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PATH.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DESCRIPTION.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_REPLYTO.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SERVER_NAME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.category"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_CATEGORY.getTypeID(),
                ATTRIBUTE_TYPE.TSK_CATEGORY.getLabel(),
                ATTRIBUTE_TYPE.TSK_CATEGORY.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_CATEGORY.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.userId"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_USER_ID.getTypeID(),
                ATTRIBUTE_TYPE.TSK_USER_ID.getLabel(),
                ATTRIBUTE_TYPE.TSK_USER_ID.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_USER_ID.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.password"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PASSWORD.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PASSWORD.getLabel(),
                ATTRIBUTE_TYPE.TSK_PASSWORD.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PASSWORD.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.personName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_NAME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.appName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.url"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_URL.getTypeID(),
                ATTRIBUTE_TYPE.TSK_URL.getLabel(),
                ATTRIBUTE_TYPE.TSK_URL.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_URL.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.appPath"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PATH.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PATH.getLabel(),
                ATTRIBUTE_TYPE.TSK_PATH.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PATH.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.description"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DESCRIPTION.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DESCRIPTION.getLabel(),
                ATTRIBUTE_TYPE.TSK_DESCRIPTION.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DESCRIPTION.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.replytoAddress"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_EMAIL_REPLYTO.getTypeID(),
                ATTRIBUTE_TYPE.TSK_EMAIL_REPLYTO.getLabel(),
                ATTRIBUTE_TYPE.TSK_EMAIL_REPLYTO.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_EMAIL_REPLYTO.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.mailServer"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_SERVER_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_SERVER_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_SERVER_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_SERVER_NAME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_ENCRYPTION_DETECTED.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.name"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_NAME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_EXT_MISMATCH_DETECTED.getTypeID() == artifactTypeId) {
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.file")),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.extension.text")),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.mimeType.text")),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.path"))}));
        } else if (ARTIFACT_TYPE.TSK_OS_INFO.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROCESSOR_ARCHITECTURE.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.processorArchitecture.text"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROCESSOR_ARCHITECTURE.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROCESSOR_ARCHITECTURE.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROCESSOR_ARCHITECTURE.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROCESSOR_ARCHITECTURE.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.osName.text"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType())),
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.osInstallDate.text"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())),
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))}));
        } else if (ARTIFACT_TYPE.TSK_EMAIL_MSG.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_TO.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SUBJECT.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_SENT.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_RCVD.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PATH.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_CC.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_BCC.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_MSG_ID.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskEmailTo"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_EMAIL_TO.getTypeID(),
                ATTRIBUTE_TYPE.TSK_EMAIL_TO.getLabel(),
                ATTRIBUTE_TYPE.TSK_EMAIL_TO.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_EMAIL_TO.getValueType())), //TSK_EMAIL_TO
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskEmailFrom"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getTypeID(),
                ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getLabel(),
                ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getValueType())), //TSK_EMAIL_FROM
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskSubject"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_SUBJECT.getTypeID(),
                ATTRIBUTE_TYPE.TSK_SUBJECT.getLabel(),
                ATTRIBUTE_TYPE.TSK_SUBJECT.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_SUBJECT.getValueType())), //TSK_SUBJECT
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskDateTimeSent"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME_SENT.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME_SENT.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME_SENT.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME_SENT.getValueType())), //TSK_DATETIME_SENT
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskDateTimeRcvd"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME_RCVD.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME_RCVD.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME_RCVD.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME_RCVD.getValueType())), //TSK_DATETIME_RCVD
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskPath"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PATH.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PATH.getLabel(),
                ATTRIBUTE_TYPE.TSK_PATH.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PATH.getValueType())), //TSK_PATH
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskEmailCc"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_EMAIL_CC.getTypeID(),
                ATTRIBUTE_TYPE.TSK_EMAIL_CC.getLabel(),
                ATTRIBUTE_TYPE.TSK_EMAIL_CC.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_EMAIL_CC.getValueType())), //TSK_EMAIL_CC
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskEmailBcc"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_EMAIL_BCC.getTypeID(),
                ATTRIBUTE_TYPE.TSK_EMAIL_BCC.getLabel(),
                ATTRIBUTE_TYPE.TSK_EMAIL_BCC.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_EMAIL_BCC.getValueType())), //TSK_EMAIL_BCC
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskMsgId"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_MSG_ID.getTypeID(),
                ATTRIBUTE_TYPE.TSK_MSG_ID.getLabel(),
                ATTRIBUTE_TYPE.TSK_MSG_ID.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_MSG_ID.getValueType()))})); //TSK_MSG_ID
        } else if (ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_CATEGORY.getTypeID()));
             String pathToShow = mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PATH.getTypeID());
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskSetName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_SET_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_SET_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_SET_NAME.getValueType())), //TSK_SET_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskInterestingFilesCategory"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_CATEGORY.getTypeID(),
                ATTRIBUTE_TYPE.TSK_CATEGORY.getLabel(),
                ATTRIBUTE_TYPE.TSK_CATEGORY.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_CATEGORY.getValueType())), //TSK_CATEGORY
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskPath"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PATH.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PATH.getLabel(),
                ATTRIBUTE_TYPE.TSK_PATH.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PATH.getValueType()))})); //TSK_PATH
        } else if (ARTIFACT_TYPE.TSK_GPS_ROUTE.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_CATEGORY.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_END.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_END.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_START.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_START.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskGpsRouteCategory"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_CATEGORY.getTypeID(),
                ATTRIBUTE_TYPE.TSK_CATEGORY.getLabel(),
                ATTRIBUTE_TYPE.TSK_CATEGORY.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_CATEGORY.getValueType())), //TSK_CATEGORY
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())), //TSK_DATETIME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.latitudeEnd"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_END.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_END.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_END.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_END.getValueType())), //TSK_GEO_LATITUDE_END
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.longitudeEnd"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_END.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_END.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_END.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_END.getValueType())), //TSK_GEO_LONGITUDE_END
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.latitudeStart"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_START.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_START.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_START.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_START.getValueType())), //TSK_GEO_LATITUDE_START
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.longitudeStart"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_START.getTypeID(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_START.getLabel(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_START.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_START.getValueType())), //TSK_GEO_LONGITUDE_START
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.name"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_NAME.getValueType())), //TSK_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.location"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getLabel(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_LOCATION.getValueType())), //TSK_LOCATION
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.program"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType()))}));//TSK_PROG_NAME
        } else if (ARTIFACT_TYPE.TSK_INTERESTING_ARTIFACT_HIT.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tskSetName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_SET_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_SET_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_SET_NAME.getValueType())), //TSK_SET_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.associatedArtifact"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getTypeID(),
                ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getLabel(),
                ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getValueType())), //TSK_ASSOCIATED_ARTIFACT
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.program"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType()))})); //TSK_PROG_NAME
        } else if (ARTIFACT_TYPE.TSK_PROG_RUN.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_COUNT.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.program"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_PROG_NAME.getValueType())), //TSK_PROG_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.associatedArtifact"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getTypeID(),
                ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getLabel(),
                ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getValueType())), //TSK_ASSOCIATED_ARTIFACT
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.dateTime"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getLabel(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_DATETIME.getValueType())), //TSK_DATETIME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.count"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_COUNT.getTypeID(),
                ATTRIBUTE_TYPE.TSK_COUNT.getLabel(),
                ATTRIBUTE_TYPE.TSK_COUNT.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_COUNT.getValueType()))})); //TSK_COUNT
        } else if (ARTIFACT_TYPE.TSK_OS_ACCOUNT.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_USER_NAME.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_USER_ID.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.userName"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_USER_NAME.getTypeID(),
                ATTRIBUTE_TYPE.TSK_USER_NAME.getLabel(),
                ATTRIBUTE_TYPE.TSK_USER_NAME.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_USER_NAME.getValueType())), //TSK_USER_NAME
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.userId"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_USER_ID.getTypeID(),
                ATTRIBUTE_TYPE.TSK_USER_ID.getLabel(),
                ATTRIBUTE_TYPE.TSK_USER_ID.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_USER_ID.getValueType()))})); //TSK_USER_ID
        } else if (ARTIFACT_TYPE.TSK_REMOTE_DRIVE.getTypeID() == artifactTypeId) {
            /*
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCAL_PATH.getTypeID()));
             orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_REMOTE_PATH.getTypeID()));
             */
            columnHeaders = new ArrayList<>(Arrays.asList(new Cell[]{
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.localPath"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_LOCAL_PATH.getTypeID(),
                ATTRIBUTE_TYPE.TSK_LOCAL_PATH.getLabel(),
                ATTRIBUTE_TYPE.TSK_LOCAL_PATH.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_LOCAL_PATH.getValueType())), //TSK_LOCAL_PATH
                new AttributeCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.remotePath"),
                new BlackboardAttribute.Type(ATTRIBUTE_TYPE.TSK_REMOTE_PATH.getTypeID(),
                ATTRIBUTE_TYPE.TSK_REMOTE_PATH.getLabel(),
                ATTRIBUTE_TYPE.TSK_REMOTE_PATH.getDisplayName(),
                ATTRIBUTE_TYPE.TSK_REMOTE_PATH.getValueType()))})); //TSK_REMOTE_PATH
        } else {
            for (BlackboardAttribute.Type type : types) {
                columnHeaders.add(new AttributeCell(type.getDisplayName(), type));
            }
            columnHeaders.add(new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile")));
        }
        columnHeaders.add(
                new OtherCell(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.tags")));

        return columnHeaders;
    }

    /**
     * Map all BlackboardAttributes' values in a list of BlackboardAttributes to
     * each attribute's attribute type ID, using module's dateToString method
     * for date/time conversions if a module is supplied.
     *
     * @param attList list of BlackboardAttributes to be mapped
     * @param module the TableReportModule the mapping is for
     *
     * @return Map<Integer, String> of the BlackboardAttributes mapped to their
     * attribute type ID
     */
    public Map<Integer, String> getMappedAttributes(List<BlackboardAttribute> attList, TableReportModule... module) {
        Map<Integer, String> attributes = new HashMap<>();
        int size = ATTRIBUTE_TYPE.values().length;
        for (int n = 0; n <= size; n++) {
            attributes.put(n, "");
        }
        for (BlackboardAttribute tempatt : attList) {
            String value = "";
            Integer type = tempatt.getAttributeTypeID();
            if (type.equals(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID())
                    || type.equals(ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getTypeID())
                    || type.equals(ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getTypeID())
                    || type.equals(ATTRIBUTE_TYPE.TSK_DATETIME_MODIFIED.getTypeID())
                    || type.equals(ATTRIBUTE_TYPE.TSK_DATETIME_SENT.getTypeID())
                    || type.equals(ATTRIBUTE_TYPE.TSK_DATETIME_RCVD.getTypeID())
                    || type.equals(ATTRIBUTE_TYPE.TSK_DATETIME_START.getTypeID())
                    || type.equals(ATTRIBUTE_TYPE.TSK_DATETIME_END.getTypeID())) {
                if (module.length > 0) {
                    value = module[0].dateToString(tempatt.getValueLong());
                } else {
                    SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                    value = sdf.format(new java.util.Date((tempatt.getValueLong() * 1000)));
                }
            } else {
                value = tempatt.getDisplayString();
            }

            if (value == null) {
                value = "";
            }
            value = EscapeUtil.escapeHtml(value);
            attributes.put(type, value);
        }
        return attributes;
    }

    /**
     * Converts a collection of strings into a single string of comma-separated
     * items
     *
     * @param items A collection of strings
     *
     * @return A string of comma-separated items
     */
    private String makeCommaSeparatedList(Collection<String> items) {
        String list = "";
        for (Iterator<String> iterator = items.iterator(); iterator.hasNext();) {
            list += iterator.next() + (iterator.hasNext() ? ", " : "");
        }
        return list;
    }

    /**
     * Given a tsk_file's obj_id, return the unique path of that file.
     *
     * @param objId tsk_file obj_id
     *
     * @return String unique path
     */
    private String getFileUniquePath(long objId) {
        try {
            AbstractFile af = skCase.getAbstractFileById(objId);
            if (af != null) {
                return af.getUniquePath();
            } else {
                return "";
            }
        } catch (TskCoreException ex) {
            errorList.add(NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.failedGetAbstractFileByID"));
            logger.log(Level.WARNING, "Failed to get Abstract File by ID.", ex); //NON-NLS
        }
        return "";

    }

    /**
     * Container class that holds data about an Artifact to eliminate duplicate
     * calls to the Sleuthkit database.
     */
    private class ArtifactData implements Comparable<ArtifactData> {

        private BlackboardArtifact artifact;
        private List<BlackboardAttribute> attributes;
        private HashSet<String> tags;
        private List<String> rowData = null;
        private List<Cell> columnHeaders = new ArrayList<>();

        ArtifactData(BlackboardArtifact artifact, List<BlackboardAttribute> attrs, HashSet<String> tags) {
            this.artifact = artifact;
            this.attributes = attrs;
            this.tags = tags;
        }

        public BlackboardArtifact getArtifact() {
            return artifact;
        }

        public List<BlackboardAttribute> getAttributes() {
            return attributes;
        }

        public HashSet<String> getTags() {
            return tags;
        }

        public long getArtifactID() {
            return artifact.getArtifactID();
        }

        public long getObjectID() {
            return artifact.getObjectID();
        }

        public void setColumnHeaders(List<Cell> columnHeaders) {
            this.columnHeaders = columnHeaders;
        }

        /**
         * Compares ArtifactData objects by the first attribute they have in
         * common in their List<BlackboardAttribute>. Should only be used on two
         * artifacts of the same type
         *
         * If all attributes are the same, they are assumed duplicates and are
         * compared by their artifact id. Should only be used with attributes of
         * the same type.
         */
        @Override
        public int compareTo(ArtifactData otherArtifactData) {
            if (columnHeaders != null) {
                List<String> thisRow = getRow();
                List<String> otherRow = otherArtifactData.getRow();
                for (int i = 0; i < thisRow.size(); i++) {
                    int compare = thisRow.get(i).compareTo(otherRow.get(i));
                    if (compare != 0) {
                        return compare;
                    }
                }
            }
            //OS TODO: figure out a way to compare artifact data without messing up rowData
            // If all attributes are the same, they're most likely duplicates so sort by artifact ID
            return ((Long) this.getArtifactID()).compareTo((Long) otherArtifactData.getArtifactID());
        }

        /**
         * Get the values for each row in the table report.
         *
         * @param columnHeaders The list of column headers that is used to find
         * the value types of custom artifacts
         * @return A list of string representing the data for this artifact.
         */
        public List<String> getRow() {
            if (rowData == null) {
                try {
                    rowData = getOrderedRowDataAsStrings(this.columnHeaders);
                    // replace null values if attribute was not defined
                    for (int i = 0; i < rowData.size(); i++) {
                        if (rowData.get(i) == null) {
                            rowData.set(i, "");
                        }
                    }
                } catch (TskCoreException ex) {
                    errorList.add(
                            NbBundle.getMessage(this.getClass(), "ReportGenerator.errList.coreExceptionWhileGenRptRow"));
                    logger.log(Level.WARNING, "Core exception while generating row data for artifact report.", ex); //NON-NLS
                    rowData = Collections.<String>emptyList();
                }
            }
            return rowData;
        }

        /**
         * Get a list of Strings with all the row values for the Artifact in the
         * correct order to be written to the report.
         *
         * @param columnHeaders The list of column headers that is used to find
         * the value types of custom artifacts
         *
         * @return List<String> row values. Values could be null if attribute is
         * not defined in artifact
         *
         * @throws TskCoreException
         */
        private List<String> getOrderedRowDataAsStrings(List<Cell> columnHeaders) throws TskCoreException {
            Map<Integer, String> mappedAttributes = getMappedAttributes();
            List<String> orderedRowData = new ArrayList<>();
            if (ARTIFACT_TYPE.TSK_WEB_BOOKMARK.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_URL.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_TITLE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_WEB_COOKIE.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_URL.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_VALUE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_WEB_HISTORY.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_URL.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_REFERRER.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_TITLE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_URL_DECODED.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_WEB_DOWNLOAD.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PATH.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_URL.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_RECENT_OBJECT.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PATH.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_INSTALLED_PROG.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_DEVICE_ATTACHED.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_ID.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_WEB_SEARCH_QUERY.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_TEXT.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DOMAIN.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_METADATA_EXIF.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_CONTACT.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_HOME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_OFFICE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_MOBILE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_MESSAGE.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_MESSAGE_TYPE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DIRECTION.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_READ_STATUS.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_TO.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SUBJECT.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_TEXT.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_CALLLOG.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_FROM.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER_TO.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_START.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DIRECTION.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_CALENDAR_ENTRY.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_CALENDAR_ENTRY_TYPE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DESCRIPTION.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_START.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_END.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_SPEED_DIAL_ENTRY.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SHORTCUT.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME_PERSON.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PHONE_NUMBER.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_BLUETOOTH_PAIRING.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DEVICE_ID.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_GPS_TRACKPOINT.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_GPS_BOOKMARK.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_GPS_LAST_KNOWN_LOCATION.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_GPS_SEARCH.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_SERVICE_ACCOUNT.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_CATEGORY.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_USER_ID.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PASSWORD.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_URL.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PATH.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DESCRIPTION.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_REPLYTO.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SERVER_NAME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_TOOL_OUTPUT.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_TEXT.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_ENCRYPTION_DETECTED.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_EXT_MISMATCH_DETECTED.getTypeID() == getArtifact().getArtifactTypeID()) {
                AbstractFile file = skCase.getAbstractFileById(getObjectID());
                if (file != null) {
                    orderedRowData.add(file.getName());
                    orderedRowData.add(file.getNameExtension());
                    String mimeType = file.getMIMEType();
                    if (mimeType == null) {
                        orderedRowData.add("");
                    } else {
                        orderedRowData.add(mimeType);
                    }
                    orderedRowData.add(file.getUniquePath());
                } else {
                    // Make empty rows to make sure the formatting is correct
                    orderedRowData.add(null);
                    orderedRowData.add(null);
                    orderedRowData.add(null);
                    orderedRowData.add(null);
                }
            } else if (ARTIFACT_TYPE.TSK_OS_INFO.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROCESSOR_ARCHITECTURE.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(getFileUniquePath(getObjectID()));
            } else if (ARTIFACT_TYPE.TSK_EMAIL_MSG.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_TO.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_FROM.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SUBJECT.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_SENT.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME_RCVD.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PATH.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_CC.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_EMAIL_BCC.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_MSG_ID.getTypeID()));
            } else if (ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_CATEGORY.getTypeID()));
                String pathToShow = mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PATH.getTypeID());
                if (pathToShow.isEmpty()) {
                    pathToShow = getFileUniquePath(getObjectID());
                }
                orderedRowData.add(pathToShow);
            } else if (ARTIFACT_TYPE.TSK_GPS_ROUTE.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_CATEGORY.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_END.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_END.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE_START.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE_START.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCATION.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
            } else if (ARTIFACT_TYPE.TSK_INTERESTING_ARTIFACT_HIT.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_SET_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
            } else if (ARTIFACT_TYPE.TSK_PROG_RUN.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_PROG_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_DATETIME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_COUNT.getTypeID()));
            } else if (ARTIFACT_TYPE.TSK_OS_ACCOUNT.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_USER_NAME.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_USER_ID.getTypeID()));
            } else if (ARTIFACT_TYPE.TSK_REMOTE_DRIVE.getTypeID() == getArtifact().getArtifactTypeID()) {
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_LOCAL_PATH.getTypeID()));
                orderedRowData.add(mappedAttributes.get(ATTRIBUTE_TYPE.TSK_REMOTE_PATH.getTypeID()));
            } else {
                for (Cell c : columnHeaders) {
                    String rowData = c.getRowData(this);
                    orderedRowData.add(rowData);
                }
            }
            orderedRowData.add(makeCommaSeparatedList(getTags()));

            return orderedRowData;
        }

        /**
         * Returns a mapping of Attribute Type ID to the String representation
         * of an Attribute Value.
         */
        private Map<Integer, String> getMappedAttributes() {
            return ReportGenerator.this.getMappedAttributes(attributes);
        }
    }

    /**
     * Get any tags associated with an artifact
     *
     * @param artifactId
     *
     * @return hash set of tag display names
     *
     * @throws SQLException
     */
    @SuppressWarnings("deprecation")
    private HashSet<String> getUniqueTagNames(long artifactId) throws TskCoreException {
        HashSet<String> uniqueTagNames = new HashSet<>();

        String query = "SELECT display_name, artifact_id FROM tag_names AS tn, blackboard_artifact_tags AS bat " + //NON-NLS 
                "WHERE tn.tag_name_id = bat.tag_name_id AND bat.artifact_id = " + artifactId; //NON-NLS

        try (CaseDbQuery dbQuery = skCase.executeQuery(query)) {
            ResultSet tagNameRows = dbQuery.getResultSet();
            while (tagNameRows.next()) {
                uniqueTagNames.add(tagNameRows.getString("display_name")); //NON-NLS
            }
        } catch (TskCoreException | SQLException ex) {
            throw new TskCoreException("Error getting tag names for artifact: ", ex);
        }

        return uniqueTagNames;

    }

    private interface Cell {

        String getColumnHeader();

        BlackboardAttribute.Type getType();

        String getRowData(ArtifactData artData);
    }

    private class AttributeCell implements Cell {

        private String columnHeader;
        private BlackboardAttribute.Type attributeType;

        /**
         * Constructs an ArtifactCell
         *
         * @param columnHeader The header text of this cell's column
         * @param attributeType The attribute type associated with this column
         */
        AttributeCell(String columnHeader, BlackboardAttribute.Type attributeType) {
            this.columnHeader = Objects.requireNonNull(columnHeader);
            this.attributeType = attributeType;
        }

        @Override
        public String getColumnHeader() {
            return this.columnHeader;
        }

        @Override
        public Type getType() {
            return this.attributeType;
        }

        @Override
        public String getRowData(ArtifactData artData) {
            List<BlackboardAttribute> attributes = artData.getAttributes();
            for (BlackboardAttribute attribute : attributes) {
                if (attribute.getAttributeType().equals(this.attributeType)) {
                    switch (attribute.getValueType()) {
                        case STRING:
                            return attribute.getValueString();
                        case INTEGER:
                            return attribute.getValueInt() + "";
                        case DOUBLE:
                            return attribute.getValueDouble() + "";
                        case LONG:
                            return attribute.getValueLong() + "";
                        case DATETIME:
                            SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                            return sdf.format(new java.util.Date((attribute.getValueLong() * 1000)));
                        case BYTE:
                            return attribute.getValueBytes().toString();

                    }
                }
            }
            return "";
        }
    }

    private class OtherCell implements Cell {

        private String columnHeader;

        OtherCell(String columnHeader) {
            this.columnHeader = columnHeader;
        }

        @Override
        public String getColumnHeader() {
            return this.columnHeader;
        }

        @Override
        public Type getType() {
            return null;
        }

        @Override
        public String getRowData(ArtifactData artData) {
            if (this.columnHeader.equals(NbBundle.getMessage(this.getClass(), "ReportGenerator.artTableColHdr.srcFile"))) {
                return getFileUniquePath(artData.getObjectID());
            }
            return "";
        }
    }
}
