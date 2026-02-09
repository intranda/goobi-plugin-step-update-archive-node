package de.intranda.goobi.plugins;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.interfaces.IEadEntry;
import org.goobi.interfaces.IFieldValue;
import org.goobi.interfaces.IMetadataField;
import org.goobi.interfaces.IMetadataGroup;
import org.goobi.interfaces.INodeType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.intranda.goobi.plugins.model.ArchiveManagementConfiguration;
import de.intranda.goobi.plugins.model.EadEntry;
import de.intranda.goobi.plugins.model.FieldValue;
import de.intranda.goobi.plugins.model.RecordGroup;
import de.intranda.goobi.plugins.persistence.ArchiveManagementManager;
import de.intranda.goobi.plugins.persistence.NodeInitializer;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Corporate;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataGroup;
import ugh.dl.MetadataGroupType;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.UGHException;

@PluginImplementation
@Log4j2
public class UpdateArchiveNodeStepPlugin implements IStepPluginVersion2 {

    private static final long serialVersionUID = -8740716011038434073L;

    @Getter
    private String title = "intranda_step_update_archive_node";
    @Getter
    private Step step;

    private Process process;

    private String returnPath;

    private String nodeIdMetadataName;
    private String nodeIdNodeName;

    private String nodeTypeParent;
    private String nodeTypeChild;

    private String archiveName;

    private SubnodeConfiguration pluginConfig;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;
        process = step.getProzess();

        // read parameters from correct block in configuration file
        pluginConfig = ConfigPlugins.getProjectAndStepConfig(title, step);

        nodeIdMetadataName = pluginConfig.getString("/identifierMetadataField");

        nodeIdNodeName = pluginConfig.getString("/identifierNodeField");

        nodeTypeParent = pluginConfig.getString("/nodeTypeBranch", "folder");
        nodeTypeChild = pluginConfig.getString("/nodeTypeLeaf", "file");

        archiveName = pluginConfig.getString("/archive");

        log.trace("UpdateArchiveNode step plugin initialized");
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return "";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        // open process metadata

        Prefs prefs = process.getRegelsatz().getPreferences();
        Fileformat fileformat = null;
        DocStruct docstruct = null;
        try {
            fileformat = process.readMetadataFile();
            docstruct = fileformat.getDigitalDocument().getLogicalDocStruct();
        } catch (UGHException | IOException | SwapException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        // load archive configuration
        ArchiveManagementConfiguration config = null;
        try {
            config = new ArchiveManagementConfiguration();
            config.readConfiguration(archiveName);
        } catch (ConfigurationException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        // get node types
        INodeType fileType = null;
        INodeType folderType = null;
        for (INodeType nodeType : config.getConfiguredNodes()) {
            if (nodeTypeParent.equals(nodeType.getNodeName())) {
                folderType = nodeType;
            } else if (nodeTypeChild.equals(nodeType.getNodeName())) {
                fileType = nodeType;
            }
        }

        // - open archive
        RecordGroup recordGroup = ArchiveManagementManager.getRecordGroupByTitle(archiveName);
        IEadEntry rootElement = ArchiveManagementManager.loadRecordGroup(recordGroup.getId());

        // find node identifying metadata in mets file
        Metadata nodeIdMetadata = null;

        for (Metadata md : docstruct.getAllMetadata()) {
            if (md.getType().getName().equals(nodeIdMetadataName)) {
                nodeIdMetadata = md;
                break;
            }
        }

        if (nodeIdMetadata != null) {
            // link exist, find existing node, update node metadata

            Integer entryId = ArchiveManagementManager.findNodeById(nodeIdNodeName, nodeIdMetadata.getValue());

            if (entryId != null) {
                log.debug("Found node with ID '{}', update existing node.", nodeIdMetadata.getValue());

                IEadEntry entry = null;
                for (IEadEntry e : rootElement.getAllNodes()) {
                    if (entryId.equals(e.getDatabaseId())) {
                        entry = e;
                        break;
                    }
                }

                NodeInitializer.initEadNodeWithMetadata(entry, config.getConfiguredFields());
                String fingerprintBeforeImport = entry.getFingerprint();
                // parse metadata

                // parse metadata
                entry.updateNodeWithProcessMetadata();

                // save, if metadata was changed
                entry.calculateFingerprint();
                String fingerprintAfterImport = entry.getFingerprint();
                if (!fingerprintBeforeImport.equals(fingerprintAfterImport)) {
                    ArchiveManagementManager.saveNode(recordGroup.getId(), entry);
                }
            } else {
                // linked entry does not exist, continue as new node
                log.debug("No node exists with ID '{}', create a new node", nodeIdMetadata.getValue());
                try {
                    createNewNode(prefs, docstruct, config, fileType, folderType, recordGroup, rootElement);
                } catch (IOException e) {
                    return PluginReturnValue.ERROR;
                }
            }

        } else {
            // no link exist, create new node, add it at the correct position, import metadata
            try {
                createNewNode(prefs, docstruct, config, fileType, folderType, recordGroup, rootElement);
            } catch (IOException e) {
                return PluginReturnValue.ERROR;
            }
        }

        return PluginReturnValue.FINISH;
    }

    private void createNewNode(Prefs prefs, DocStruct docstruct, ArchiveManagementConfiguration config, INodeType fileType,
            INodeType folderType,
            RecordGroup recordGroup, IEadEntry rootElement) throws IOException {
        String parentNodeId = null;
        // find ancestor element
        switch (pluginConfig.getString("/parentType")) {
            case "fixed":
                // option 1: element id is configured (per doctype)
                parentNodeId = pluginConfig.getString("/parentNodeId[@doctype='" + docstruct.getType().getName() + "']");
                if (StringUtils.isBlank(parentNodeId)) {
                    parentNodeId = pluginConfig.getString("/defaultParentNodeId");
                }
                break;

            case "metadata":
                // option 2: node id is set in a metadata field
                String parentIdMetadataField = pluginConfig.getString("/parentNodeMetadataName");
                List<? extends Metadata> parentIds = docstruct.getAllMetadataByType(prefs.getMetadataTypeByName(parentIdMetadataField));
                if (parentIds.isEmpty()) {
                    log.info("Parent element not found, abort");
                    throw new FileNotFoundException("Parent element not found, abort");

                }
                parentNodeId = parentIds.get(0).getValue();
                break;

            case "hierarchy":

                // option 3: build hierarchy from a metadata value

                String hierarchyMetadataName = pluginConfig.getString("/hierarchyMetadataName");
                String hierarchySplitChar = pluginConfig.getString("/hierarchyMetadataName/@split");

                List<? extends Metadata> mdl = docstruct.getAllMetadataByType(prefs.getMetadataTypeByName(hierarchyMetadataName));
                if (mdl.isEmpty()) {
                    log.error("Hierarchical metadata value not found, abort");
                    throw new FileNotFoundException("Hierarchical metadata value not found, abort");
                }
                String metadataValue = mdl.get(0).getValue();

                // CR_1_C_St_30 ->  CR, 1, C, ...
                String[] hierarchyParts = metadataValue.split(hierarchySplitChar);

                // build identifier with parts:
                // CR, CR_1, CR_1_C, CR_1_C_St, CR_1_C_St_30

                String currentHierarchy = "";

                Integer lastElementId = null;
                IEadEntry lastAncestorNode = rootElement;

                for (int i = 0; i < hierarchyParts.length; i++) {
                    String part = hierarchyParts[i];

                    if (StringUtils.isNotBlank(currentHierarchy)) {
                        currentHierarchy = currentHierarchy + "_";
                    }
                    currentHierarchy = currentHierarchy + part;

                    Integer currentId = ArchiveManagementManager.findNodeById(nodeIdNodeName, currentHierarchy);

                    if (currentId != null) {
                        // element exist, continue with next part
                        lastElementId = currentId;
                        for (IEadEntry e : rootElement.getAllNodes()) {
                            if (e.getDatabaseId().equals(lastElementId)) {
                                lastAncestorNode = e;
                                break;
                            }
                        }
                    } else {
                        // order within parent element: if current part is numeric, use this as position, otherwise insert it as last element
                        int orderNumber = StringUtils.isNumeric(part) ? Integer.parseInt(part) : lastAncestorNode.getSubEntryList().size() + 1;

                        // create new node as child of the last existing ancestor element (or root element, if no parent exists)
                        EadEntry entry = createNode(config, lastAncestorNode, orderNumber, currentHierarchy);

                        // current hierarchy is the last element
                        if (i == hierarchyParts.length - 1) {
                            // set node type file
                            entry.setNodeType(fileType);

                            // parse metadata
                            parseMetadata(prefs, entry, docstruct);

                            entry.setGoobiProcessTitle(process.getTitel());

                            try {
                                // save identifier metadata
                                Metadata md = new Metadata(prefs.getMetadataTypeByName("NodeId"));
                                md.setValue(entry.getId());
                                docstruct.addMetadata(md);
                            } catch (MetadataTypeNotAllowedException e) {
                                log.error(e);
                            }

                        } else {
                            // set node type folder
                            entry.setNodeType(folderType);
                        }

                        // save node
                        lastAncestorNode.addSubEntry(entry);
                        lastAncestorNode.sortElements();
                        lastAncestorNode.updateHierarchy();
                        entry.calculateFingerprint();

                        ArchiveManagementManager.saveNode(recordGroup.getId(), entry);
                        lastElementId = entry.getDatabaseId();
                        ArchiveManagementManager.updateNodeHierarchy(recordGroup.getId(), lastAncestorNode.getAllNodes());

                        lastAncestorNode = entry;
                    }
                }
                break;
        }

        if (StringUtils.isNotBlank(parentNodeId)) {
            // load configured node

            Integer parentId = ArchiveManagementManager.findNodeById(nodeIdNodeName, parentNodeId);
            IEadEntry ancestorNode = null;

            if (parentId != null) {
                // element exist, continue with next part
                for (IEadEntry e : rootElement.getAllNodes()) {
                    if (e.getDatabaseId().equals(parentId)) {
                        ancestorNode = e;
                        break;
                    }
                }
            }
            if (ancestorNode == null) {
                log.error("Cannot find node with id {}", parentNodeId);
                throw new FileNotFoundException("Parent element not found, abort");
            }

            int orderNumber = ancestorNode.getSubEntryList().size() + 1;

            // create new node as child of the ancestor element
            EadEntry entry = createNode(config, ancestorNode, orderNumber, "");
            // set node type
            entry.setNodeType(fileType);

            // parse metadata
            parseMetadata(prefs, entry, docstruct);
            entry.setGoobiProcessTitle(process.getTitel());

            try {
                // save identifier metadata
                Metadata md = new Metadata(prefs.getMetadataTypeByName("NodeId"));
                md.setValue(entry.getId());
                docstruct.addMetadata(md);
            } catch (MetadataTypeNotAllowedException e) {
                log.error(e);
            }

            // save
            ancestorNode.addSubEntry(entry);
            ancestorNode.sortElements();
            ancestorNode.updateHierarchy();
            entry.calculateFingerprint();
            ArchiveManagementManager.saveNode(recordGroup.getId(), entry);
            ArchiveManagementManager.updateNodeHierarchy(recordGroup.getId(), ancestorNode.getAllNodes());
        }
    }

    private EadEntry createNode(ArchiveManagementConfiguration config, IEadEntry lastAncestorNode, int orderNumber, String identifier) {
        EadEntry entry = new EadEntry(orderNumber, lastAncestorNode.getHierarchy() + 1);

        // generate a new internal id
        entry.setId("id_" + UUID.randomUUID());
        for (IMetadataField emf : config.getConfiguredFields()) {
            if (emf.isGroup()) {
                NodeInitializer.loadGroupMetadata(entry, emf, null);
            } else {
                IMetadataField toAdd = NodeInitializer.addFieldToEntry(entry, emf, null);
                NodeInitializer.addFieldToNode(entry, toAdd);

            }
        }

        entry.setLabel(identifier);

        IMetadataField titleField = entry.getFieldByName("unittitle");
        if (titleField != null) {
            titleField.getValues().get(0).setValue(identifier);
        }

        // find parser.getArchiveIdentifierField() field, set value
        IMetadataField identifierField = entry.getFieldByName(nodeIdNodeName);
        identifierField.getValues().get(0).setValue(identifier);

        return entry;
    }

    private void parseMetadata(Prefs prefs, EadEntry entry, DocStruct docstruct) {
        for (IMetadataField emf : entry.getIdentityStatementAreaList()) {
            if (emf.isGroup()) {
                importGroupData(prefs, docstruct, emf);
            } else if (StringUtils.isNotBlank(emf.getMetadataName())) {
                importMetadata(prefs, docstruct, emf);
            }
        }

        for (IMetadataField emf : entry.getContextAreaList()) {
            if (emf.isGroup()) {
                importGroupData(prefs, docstruct, emf);
            } else if (StringUtils.isNotBlank(emf.getMetadataName())) {
                importMetadata(prefs, docstruct, emf);
            }
        }

        for (IMetadataField emf : entry.getContentAndStructureAreaAreaList()) {
            if (emf.isGroup()) {
                importGroupData(prefs, docstruct, emf);
            } else if (StringUtils.isNotBlank(emf.getMetadataName())) {
                importMetadata(prefs, docstruct, emf);
            }
        }

        for (IMetadataField emf : entry.getAccessAndUseAreaList()) {
            if (emf.isGroup()) {
                importGroupData(prefs, docstruct, emf);
            } else if (StringUtils.isNotBlank(emf.getMetadataName())) {
                importMetadata(prefs, docstruct, emf);
            }
        }

        for (IMetadataField emf : entry.getAlliedMaterialsAreaList()) {
            if (emf.isGroup()) {
                importGroupData(prefs, docstruct, emf);
            } else if (StringUtils.isNotBlank(emf.getMetadataName())) {
                importMetadata(prefs, docstruct, emf);
            }
        }

        for (IMetadataField emf : entry.getNotesAreaList()) {
            if (emf.isGroup()) {
                importGroupData(prefs, docstruct, emf);
            } else if (StringUtils.isNotBlank(emf.getMetadataName())) {
                importMetadata(prefs, docstruct, emf);
            }
        }

        for (IMetadataField emf : entry.getDescriptionControlAreaList()) {
            if (emf.isGroup()) {
                importGroupData(prefs, docstruct, emf);
            } else if (StringUtils.isNotBlank(emf.getMetadataName())) {
                importMetadata(prefs, docstruct, emf);
            }
        }

    }

    private void importGroupData(Prefs prefs, DocStruct docstruct, IMetadataField emf) {
        if (StringUtils.isNotBlank(emf.getMetadataName())) {
            MetadataGroupType mgt = prefs.getMetadataGroupTypeByName(emf.getMetadataName());
            List<MetadataGroup> grps = docstruct.getAllMetadataGroupsByType(mgt);
            if (grps != null && !grps.isEmpty()) {
                // clear existing groups
                for (MetadataGroup grp : grps) {
                    IMetadataGroup eadGroup = emf.createGroup();
                    for (IMetadataField sub : eadGroup.getFields()) {
                        List<Metadata> meta = grp.getMetadataByType(sub.getMetadataName());
                        if (meta != null) {
                            List<IFieldValue> list = new ArrayList<>();
                            sub.setValues(list);
                            for (Metadata md : meta) {
                                IFieldValue val = new FieldValue(emf);
                                val.setValue(md.getValue());
                                val.setAuthorityValue(md.getAuthorityValue());
                                sub.addFieldValue(val);
                            }
                        }
                    }
                }
            }
        }
    }

    private void importMetadata(Prefs prefs, DocStruct docstruct, IMetadataField emf) {
        if (StringUtils.isNotBlank(emf.getMetadataName())) {
            MetadataType mdt = prefs.getMetadataTypeByName(emf.getMetadataName());

            // clear all fields
            List<IFieldValue> list = new ArrayList<>();
            // emf.setValues(list);
            if ("person".equals(emf.getFieldType())) {
                List<Person> persons = docstruct.getAllPersonsByType(mdt);
                if (persons != null && !persons.isEmpty()) {
                    emf.setValues(list);
                    for (Person p : persons) {
                        IFieldValue val = new FieldValue(emf);
                        val.setFirstname(p.getFirstname());
                        val.setLastname(p.getLastname());
                        val.setAuthorityValue(p.getAuthorityValue());
                        emf.addFieldValue(val);
                    }
                }
            } else if ("corporate".equals(emf.getFieldType())) {
                List<Corporate> corporates = docstruct.getAllCorporatesByType(mdt);
                if (corporates != null && !corporates.isEmpty()) {
                    emf.setValues(list);
                    for (Corporate c : corporates) {
                        IFieldValue val = new FieldValue(emf);
                        val.setMainName(c.getMainName());
                        if (!c.getSubNames().isEmpty()) {
                            val.setSubName(c.getSubNames().get(0).getValue());
                        }
                        val.setPartName(c.getPartName());
                        val.setAuthorityValue(c.getAuthorityValue());
                        emf.addFieldValue(val);
                    }
                }
            } else {
                List<? extends Metadata> metadataList = docstruct.getAllMetadataByType(mdt);
                if (metadataList != null && !metadataList.isEmpty()) {
                    emf.setValues(list);
                    for (Metadata md : metadataList) {
                        IFieldValue val = new FieldValue(emf);
                        val.setValue(md.getValue());
                        val.setAuthorityValue(md.getAuthorityValue());
                        emf.addFieldValue(val);
                    }
                }
            }
        }
    }
}
