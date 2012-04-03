/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2009.                            (c) 2009.
*  Government of Canada                 Gouvernement du Canada
*  National Research Council            Conseil national de recherches
*  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
*  All rights reserved                  Tous droits réservés
*                                       
*  NRC disclaims any warranties,        Le CNRC dénie toute garantie
*  expressed, implied, or               énoncée, implicite ou légale,
*  statutory, of any kind with          de quelque nature que ce
*  respect to the software,             soit, concernant le logiciel,
*  including without limitation         y compris sans restriction
*  any warranty of merchantability      toute garantie de valeur
*  or fitness for a particular          marchande ou de pertinence
*  purpose. NRC shall not be            pour un usage particulier.
*  liable in any event for any          Le CNRC ne pourra en aucun cas
*  damages, whether direct or           être tenu responsable de tout
*  indirect, special or general,        dommage, direct ou indirect,
*  consequential or incidental,         particulier ou général,
*  arising from the use of the          accessoire ou fortuit, résultant
*  software.  Neither the name          de l'utilisation du logiciel. Ni
*  of the National Research             le nom du Conseil National de
*  Council of Canada nor the            Recherches du Canada ni les noms
*  names of its contributors may        de ses  participants ne peuvent
*  be used to endorse or promote        être utilisés pour approuver ou
*  products derived from this           promouvoir les produits dérivés
*  software without specific prior      de ce logiciel sans autorisation
*  written permission.                  préalable et particulière
*                                       par écrit.
*                                       
*  This file is part of the             Ce fichier fait partie du projet
*  OpenCADC project.                    OpenCADC.
*                                       
*  OpenCADC is free software:           OpenCADC est un logiciel libre ;
*  you can redistribute it and/or       vous pouvez le redistribuer ou le
*  modify it under the terms of         modifier suivant les termes de
*  the GNU Affero General Public        la “GNU Affero General Public
*  License as published by the          License” telle que publiée
*  Free Software Foundation,            par la Free Software Foundation
*  either version 3 of the              : soit la version 3 de cette
*  License, or (at your option)         licence, soit (à votre gré)
*  any later version.                   toute version ultérieure.
*                                       
*  OpenCADC is distributed in the       OpenCADC est distribué
*  hope that it will be useful,         dans l’espoir qu’il vous
*  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
*  without even the implied             GARANTIE : sans même la garantie
*  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
*  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
*  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
*  General Public License for           Générale Publique GNU Affero
*  more details.                        pour plus de détails.
*                                       
*  You should have received             Vous devriez avoir reçu une
*  a copy of the GNU Affero             copie de la Licence Générale
*  General Public License along         Publique GNU Affero avec
*  with OpenCADC.  If not, sesrc/jsp/index.jspe          OpenCADC ; si ce n’est
*  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
*                                       <http://www.gnu.org/licenses/>.
*
*  $Revision: 4 $
*
************************************************************************
*/

package ca.nrc.cadc.vos.server;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.security.auth.Subject;
import javax.sql.DataSource;

import org.apache.log4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import ca.nrc.cadc.auth.IdentityManager;
import ca.nrc.cadc.date.DateUtil;
import ca.nrc.cadc.util.CaseInsensitiveStringComparator;
import ca.nrc.cadc.util.FileMetadata;
import ca.nrc.cadc.util.HexUtil;
import ca.nrc.cadc.vos.ContainerNode;
import ca.nrc.cadc.vos.DataNode;
import ca.nrc.cadc.vos.Node;
import ca.nrc.cadc.vos.NodeProperty;
import ca.nrc.cadc.vos.VOS;
import ca.nrc.cadc.vos.VOSURI;
import ca.nrc.cadc.vos.VOS.NodeBusyState;
import java.util.LinkedList;

/**
 * Helper class for implementing NodePersistence with a
 * relational database back end for metadata. This class is
 * NOT thread-safe and ten caller must instantiate a new one
 * in each thread (e.g. app-server request thread).
 *
 * @author majorb
 */
public class NodeDAO
{
    private static Logger log = Logger.getLogger(NodeDAO.class);
    private static final int CHILD_BATCH_SIZE = 1000;

    // temporarily needed by NodeMapper
    static final String NODE_TYPE_DATA = "D";
    static final String NODE_TYPE_CONTAINER = "C";
    
    private static final int NODE_NAME_COLUMN_SIZE = 256;
    private static final int MAX_TIMESTAMP_LENGTH = 30;

    // Database connection.
    protected DataSource dataSource;
    protected NodeSchema nodeSchema;
    protected String authority;
    protected IdentityManager identManager;

    protected JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private DefaultTransactionDefinition defaultTransactionDef;
    private TransactionStatus transactionStatus;

    private DateFormat dateFormat;
    private Calendar cal;

    private Map<Object,Subject> identityCache = new HashMap<Object,Subject>();

    public static class NodeSchema
    {
        public String nodeTable;
        public String propertyTable;
        boolean limitWithTop;
        boolean fileMetadataWritable;

        /**
         * Constructor for specifying the table where Node(s) and NodeProperty(s) are
         * stored.
         * @param nodeTable fully qualified name of node table
         * @param propertyTable fully qualified name of property table
         * @param limitWithTop - true if the RDBMS uses TOP, false for LIMIT
         * @param fileMetadataWritable true if the contentLength and contentMD5 properties
         * are writable, false if they are read-only in the DB
         */
        public NodeSchema(String nodeTable, String propertyTable,
                boolean limitWithTop,
                boolean fileMetadataWritable)
        {
            this.nodeTable = nodeTable;
            this.propertyTable = propertyTable;
            this.limitWithTop = limitWithTop;
            this.fileMetadataWritable = fileMetadataWritable;
        }
        
    }
    private static String[] NODE_COLUMNS = new String[]
    {
        "parentID", // FK, for join to parent
        "name",
        "type",
        "busyState",
        "isPublic",
        "ownerID",
        "creatorID",
        "groupRead",
        "groupWrite",
        "lastModified",
        // semantic file metadata
        "contentType",
        "contentEncoding",
        // physical file metadata
        "nodeSize",
        "contentLength",
        "contentMD5"
    };

    /**
     * NodeDAO Constructor. This class was developed and tested using a
     * Sybase ASE RDBMS. Some SQL (update commands in particular) may be non-standard.
     *
     * @param dataSource
     * @param nodeSchema
     * @param authority
     * @param identManager 
     */
    public NodeDAO(DataSource dataSource, NodeSchema nodeSchema, String authority, IdentityManager identManager)
    {
        this.dataSource = dataSource;
        this.nodeSchema = nodeSchema;
        this.authority = authority;
        this.identManager = identManager;

        this.defaultTransactionDef = new DefaultTransactionDefinition();
        defaultTransactionDef.setIsolationLevel(DefaultTransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactionManager = new DataSourceTransactionManager(dataSource);

        this.dateFormat = DateUtil.getDateFormat(DateUtil.IVOA_DATE_FORMAT, DateUtil.UTC);
        this.cal = Calendar.getInstance(DateUtil.UTC);
    }

    // convenience during refactor
    protected String getNodeTableName()
    {
        return nodeSchema.nodeTable;
    }

    // convenience during refactor
    protected String getNodePropertyTableName()
    {
        return nodeSchema.propertyTable;
    }
    
    /**
     * Start a transaction to the data source.
     */
    protected void startTransaction()
    {
        if (transactionStatus != null)
            throw new IllegalStateException("transaction already in progress");
        log.debug("startTransaction");
        this.transactionStatus = transactionManager.getTransaction(defaultTransactionDef);
        log.debug("startTransaction: OK");
    }

    /**
     * Commit the transaction to the data source.
     */
    protected void commitTransaction()
    {
        if (transactionStatus == null)
            throw new IllegalStateException("no transaction in progress");
        log.debug("commitTransaction");
        transactionManager.commit(transactionStatus);
        this.transactionStatus = null;
        log.debug("commit: OK");
    }

    /**
     * Rollback the transaction to the data source.
     */
    protected void rollbackTransaction()
    {
        if (transactionStatus == null)
            throw new IllegalStateException("no transaction in progress");
        log.debug("rollbackTransaction");
        transactionManager.rollback(transactionStatus);
        this.transactionStatus = null;
        log.debug("rollback: OK");
    }

    /**
     * Checks that the specified node has already been persisted. This will pass if this
     * node was obtained from the getPath method.
     * @param node
     */
    protected void expectPersistentNode(Node node)
    {
        if (node == null)
            throw new IllegalArgumentException("node cannot be null");
        if (node.appData == null)
            throw new IllegalArgumentException("node is not a persistent node: " + node.getUri().getPath());
    }

    /**
     * Get a complete path from the root container. For the container nodes in
     * the returned node, only child nodes on the path will be included in the
     * list of children; other children are not included. Nodes returned from
     * this method will have some but not all properties set. Specifically, any
     * properties that are inherently single-valued and stored in the Node table
     * are included, as are the access-control properties (isPublic, group-read, 
     * and group-write). The remaining properties for a node can be obtained by
     * calling getProperties(Node).
     *
     * @see getProperties(Node)
     * @param path
     * @return the last node in the path, with all parents or null if not found
     */
    public Node getPath(String path)
    {
        log.debug("getPath: " + path);
        if (path.length() > 0 && path.charAt(0) == '/')
            path = path.substring(1);
        // generate single join query to extract path
        NodePathStatementCreator npsc = new NodePathStatementCreator(
                path.split("/"), getNodeTableName(), getNodePropertyTableName());

        // execute query with NodePathExtractor
        Node ret = (Node) jdbc.query(npsc, new NodePathExtractor());
        loadSubjects(ret);

        return ret;
    }

    /**
     * Load all the properties for the specified Node.
     * 
     * @param node
     */
    public void getProperties(Node node)
    {
        log.debug("getProperties: " + node.getUri().getPath() + ", " + node.getClass().getSimpleName());
        expectPersistentNode(node);

        log.debug("getProperties: " + node.getUri().getPath() + ", " + node.getClass().getSimpleName());
        String sql = getSelectNodePropertiesByID(node);
        log.debug("getProperties: " + sql);
        List<NodeProperty> props = jdbc.query(sql, new NodePropertyMapper());
        node.getProperties().addAll(props);
    }

    /**
     * Load a single child node of the specified container.
     * 
     * @param parent
     * @param name
     */
    public void getChild(ContainerNode parent, String name)
    {
        log.debug("getChild: " + parent.getUri().getPath() + ", " + name);
        expectPersistentNode(parent);

        String sql = getSelectChildNodeSQL(parent);
        log.debug("getChild: " + sql);
        List<Node> nodes = jdbc.query(sql, new Object[] { name }, 
                new NodeMapper(authority, parent.getUri().getPath()));
        if (nodes.size() > 1)
            throw new IllegalStateException("BUG - found " + nodes.size() + " child nodes named " + name
                    + " for container " + parent.getUri().getPath());
        loadSubjects(nodes);
        addChildNodes(parent, nodes);
    }

    /**
     * Load all the child nodes of the specified container.
     *
     * @param parent
     */
    public void getChildren(ContainerNode parent)
    {
        log.debug("getChildren: " + parent.getUri().getPath() + ", " + parent.getClass().getSimpleName());
        getChildren(parent, null, null);
    }
    
    /**
     * Loads some of thre child nodes of the specified container.
     * @param parent
     * @param start
     * @param limit
     */
    public void getChildren(ContainerNode parent, VOSURI start, Integer limit)
    {
        log.debug("getChildren: " + parent.getUri().getPath() + ", " + parent.getClass().getSimpleName());
        expectPersistentNode(parent);

        Object[] args = null;
        String startName = null;
        if (start != null)
            args = new Object[] { start.getName() };
        else
            args = new Object[0];
        
        // we must re-run the query in case server-side content changed since the argument node
        // was called, e.g. from delete(node) or markForDeletion(node)
        String sql = getSelectNodesByParentSQL(parent, limit, (start!=null));
        log.debug("getChildren: " + sql);
        List<Node> nodes = jdbc.query(sql,  args,
                new NodeMapper(authority, parent.getUri().getPath()));
        loadSubjects(nodes);
        addChildNodes(parent, nodes);
    }

    /**
     * Add the provided children to the parent.
     */
    private void addChildNodes(ContainerNode parent, List<Node> nodes)
    {
        if (parent.getNodes().isEmpty())
        {
            for (Node n : nodes)
            {
                log.debug("adding child to list: " + n.getUri().getPath());
                parent.getNodes().add(n);
                n.setParent(parent);
            }
        }
        else
        {
            // 'nodes' will not have duplicates, but 'parent.getNodes()' may
            // already contain some of 'nodes'.
            List<Node> existingChildren = new ArrayList<Node>(parent.getNodes().size());
            existingChildren.addAll(parent.getNodes());
            for (Node n : nodes)
            {
                if (!existingChildren.contains(n))
                {
                    log.debug("adding child to list: " + n.getUri().getPath());
                    n.setParent(parent);
                    parent.getNodes().add(n);
                }
                else
                    log.debug("child already in list, not adding: " + n.getUri().getPath());
            }
        }
    }
    
    private void loadSubjects(List<Node> nodes)
    {
        for (Node n : nodes)
            loadSubjects(n);
    }
    
    private void loadSubjects(Node node)
    {
        if (node == null || node.appData == null)
            return;

        NodeID nid = (NodeID) node.appData;
        if (nid.owner != null)
            return; // already loaded (parent loop below)

        Subject s = identityCache.get(nid.ownerObject);
        if (s == null)
        {
            log.debug("lookup subject for owner=" + nid.ownerObject);
            s = identManager.toSubject(nid.ownerObject);
            identityCache.put(nid.ownerObject, s);
        }
        else
            log.debug("found cached subject for owner=" + nid.ownerObject);
        nid.owner = s;
        String owner = identManager.toOwnerString(nid.owner);
        if (owner != null)
            node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_CREATOR, owner));
        Node parent = node.getParent();
        while (parent != null)
        {
            loadSubjects(parent);
            parent = parent.getParent();
        }
    }

    /**
     * Store the specified node. The node must be attached to a parent container and
     * the parent container must have already been persisted.
     * 
     * @param node
     * @param creator
     * @return the same node but with generated internal ID set in the appData field
     */
    public Node put(Node node, Subject creator)
    {
        log.debug("put: " + node.getUri().getPath() + ", " + node.getClass().getSimpleName());

        // if parent is null, this is just a new root-level node,
        if (node.getParent() != null && node.getParent().appData == null)
            throw new IllegalArgumentException("parent of node is not a persistent node: " + node.getUri().getPath());

        if (node.appData != null) // persistent node == update == not supported
            throw new UnsupportedOperationException("update of existing node not supported; try updateProperties");

        if (node.getName().length() > NODE_NAME_COLUMN_SIZE)
            throw new IllegalArgumentException("length of node name exceeds limit ("+NODE_NAME_COLUMN_SIZE+"): " + node.getName());

        try
        {
            // call IdentityManager outside resource lock to avoid deadlock
            NodeID nodeID = new NodeID();
            nodeID.owner = creator;
            nodeID.ownerObject = identManager.toOwner(creator);
            node.appData = nodeID;

            startTransaction();
            NodePutStatementCreator npsc = new NodePutStatementCreator(nodeSchema, false);
            npsc.setValues(node, null);
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(npsc, keyHolder);
            nodeID.id = new Long(keyHolder.getKey().longValue());
            
            Iterator<NodeProperty> propertyIterator = node.getProperties().iterator();
            while (propertyIterator.hasNext())
            {
                NodeProperty prop = propertyIterator.next();
                if ( usePropertyTable(prop.getPropertyURI()) )
                {
                    PropertyStatementCreator ppsc = new PropertyStatementCreator(nodeSchema, nodeID, prop, false);
                    jdbc.update(ppsc);
                }
                // else: already persisted by the NodePutStatementCreator above
                // note: very important that the node owner (creator property) is excluded
                // by the above usePropertyTable returning false
            }

            commitTransaction();

            node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_CREATOR, identManager.toOwnerString(creator)));
            return node;
        }
        catch(Throwable t)
        {
            log.error("rollback for node: " + node.getUri().getPath(), t);
            try { rollbackTransaction(); }
            catch(Throwable oops) { log.error("failed to rollback transaction", oops); }
            throw new RuntimeException("failed to persist node: " + node.getUri(), t);
        }
        finally
        {
            if (transactionStatus != null)
                try
                {
                    log.warn("put: BUG - transaction still open in finally... calling rollback");
                    rollbackTransaction();
                }
                catch(Throwable oops) { log.error("failed to rollback transaction in finally", oops); }
        }
    }

    /**
     * Recursive delete of a node. This method irrevocably deletes a node and all
     * the child nodes below it.
     * 
     * @param node
     */
    public void delete(Node node)
    {
        log.debug("delete: " + node.getUri().getPath() + ", " + node.getClass().getSimpleName());
        expectPersistentNode(node);

        try
        {
            startTransaction();

            // update nodeSize(s) from top down to node
            long delta = getContentLength(node);
            if (delta > 0)
            {
                delta = -1*delta;
                LinkedList<Node> nodes = Node.getNodeList(node.getParent());
                Iterator<Node> i = nodes.descendingIterator();
                while ( i.hasNext() )
                {
                    Node n = i.next();
                    String sql = getUpdateNodeSizeSQL(n, delta);
                    log.debug(sql);
                    jdbc.update(sql);
                }
            }
            deleteNode(node);
            
            commitTransaction();
            log.debug("Node deleted: " + node.getUri().getPath());
        }
        catch (Throwable t)
        {
            log.error("Delete rollback for node: " + node.getUri().getPath(), t);
            if (transactionStatus != null)
                try { rollbackTransaction(); }
                catch(Throwable oops) { log.error("failed to rollback transaction", oops); }
            throw new RuntimeException("failed to delete " + node.getUri().getPath(), t);
        }
        finally
        {
            if (transactionStatus != null)
                try
                {
                    log.warn("delete - BUG - transaction still open in finally... calling rollback");
                    rollbackTransaction();
                }
                catch(Throwable oops) { log.error("failed to rollback transaction in finally", oops); }
        }
    }

    private void deleteNode(Node node)
    {
        log.debug("deleteNode: " + node.getUri().getPath() + ", " + node.getClass().getSimpleName());
        // depth-first: delete children
        if (node instanceof ContainerNode)
        {
            ContainerNode cn = (ContainerNode) node;
            deleteChildren(cn);
        }

        // delete properties: FK constraint -> node
        String sql = getDeleteNodePropertiesSQL(node);
        log.debug(sql);
        jdbc.update(sql);

        // never delete a top-level node?
        if (node.getParent() != null)
        {
            sql = getDeleteNodeSQL(node);
            log.debug(sql);
            jdbc.update(sql);
        }
    }

    private void deleteChildren(ContainerNode node)
    {
        log.debug("deleteChildren: " + node.getUri().getPath() + ", " + node.getClass().getSimpleName());
        getChildren(node);
        Iterator<Node> iter = node.getNodes().iterator();
        while ( iter.hasNext() )
        {
            Node n = iter.next();
            deleteNode(n);
        }
    }

    /**
     * Change the busy stateof a node from a known state to another.
     * 
     * @param node
     * @param state
     */
    public VOS.NodeBusyState setBusyState(DataNode node, NodeBusyState curState, NodeBusyState newState)
    {
        log.debug("setBusyState: " + node.getUri().getPath() + ", " + curState + " -> " + newState);
        expectPersistentNode(node);

        try
        {
            String sql = getSetBusyStateSQL(node, curState, newState);
            log.debug(sql);
            int num = jdbc.update(sql);
            if (num == 1)
                return newState;
            return null;
        }
        catch (Throwable t)
        {
            throw new RuntimeException("failed to set busy state: " + node.getUri().getPath(), t);
        }
    }

    /**
     * Update the size of all nodes in the path a specified increment or
     * decrement, plus set BusyState of the DataNode to  not-busy.
     *
     * @param node
     * @param delta amount to increment (+) or decrement (-)
     */
    public void updateNodeMetadata(DataNode node, FileMetadata meta)
    {
        log.debug("updateNodeMetadata: " + node.getUri().getPath());

        expectPersistentNode(node);
        
        // old size is in the nodeSize column
        // new size is in meta.getContentLength() (NodeSchema.fileMetaWritable)
        // or already in contentLength column (!NodeSchema.fileMetaWritable)
        
        String select = "SELECT contentLength, nodeSize FROM " + getNodeTableName()
                + " WHERE nodeID = " + getNodeID(node);
        log.debug(select);
        Long[] sizes = (Long[]) jdbc.query(select, new ResultSetExtractor()
            {

                public Object extractData(ResultSet rs) throws SQLException, DataAccessException
                {
                    if ( rs.next() )
                    {
                        Long cl = rs.getLong("contentLength"); // JDBC: NULL -> 0
                        Long ns = rs.getLong("nodeSize");      // JDBC: NULL -> 0
                        return new Long[] { cl, ns };
                    }
                    return null;
                }
            } );

        Long len = sizes[0];
        Long nodeSize = sizes[1];
        if ( nodeSchema.fileMetadataWritable)
            len = meta.getContentLength();
        long delta = delta = nodeSize - len;

        String contentLength = Long.toString(len);

        try
        {
            startTransaction();

            // first, update nodes from the top down the path
            if (delta != 0)
            {
                LinkedList<Node> nodes = Node.getNodeList(node);
                Iterator<Node> i = nodes.descendingIterator();
                while ( i.hasNext() )
                {
                    Node n = i.next();
                    String sql = getUpdateNodeSizeSQL(n, delta);
                    log.debug(sql);
                    jdbc.update(sql);
                }
            }

            // last, update the busy state of the target node
            String trans = getSetBusyStateSQL(node, NodeBusyState.busyWithWrite, NodeBusyState.notBusy);
            log.debug(trans);
            int num = jdbc.update(trans);
            if (num != 1)
                throw new IllegalStateException("updateFileMetadata requires a node with busyState=W: "+node.getUri());

            // now safe to update properties of the target node
            List<NodeProperty> props = new ArrayList<NodeProperty>();
            NodeProperty np;

            np = findOrCreate(node, VOS.PROPERTY_URI_CONTENTLENGTH, contentLength);
            if (np != null)
                props.add(np);

            np = findOrCreate(node, VOS.PROPERTY_URI_CONTENTENCODING, meta.getContentEncoding());
            if (np != null)
                props.add(np);
            np = findOrCreate(node, VOS.PROPERTY_URI_TYPE, meta.getContentType());
            if (np != null)
                props.add(np);
            np = findOrCreate(node, VOS.PROPERTY_URI_CONTENTMD5, meta.getMd5Sum());
            if (np != null)
                props.add(np);

            doUpdateProperties(node, props);
           
            commitTransaction();
        }
        catch(IllegalStateException ex)
        {
            log.debug("updateNodeMetadata rollback for node (!busy): " + node.getUri().getPath());
            if (transactionStatus != null)
                try { rollbackTransaction(); }
                catch(Throwable oops) { log.error("failed to rollback transaction", oops); }
            throw ex;
        }
        catch (Throwable t)
        {
            log.error("Delete rollback for node: " + node.getUri().getPath(), t);
            if (transactionStatus != null)
                try { rollbackTransaction(); }
                catch(Throwable oops) { log.error("failed to rollback transaction", oops); }
            throw new RuntimeException("failed to updateNodeMetadata " + node.getUri().getPath(), t);
        }
        finally
        {
            if (transactionStatus != null)
                try
                {
                    log.warn("delete - BUG - transaction still open in finally... calling rollback");
                    rollbackTransaction();
                }
                catch(Throwable oops) { log.error("failed to rollback transaction in finally", oops); }
        }
    }

    // find existing prop, mark for delete if value is null or set value
    // create new prop with value
    private NodeProperty findOrCreate(Node node, String uri, String value)
    {
        NodeProperty np = node.findProperty(VOS.PROPERTY_URI_CONTENTLENGTH);
        if (np == null && value == null)
            return null;

        if (value == null)
            np.setMarkedForDeletion(true);
        else if (np == null)
            np = new NodeProperty(uri, value);
        else
            np.setValue(value);

        return np;
    }
    
    /**
     * Update the properties associated with this node.  New properties are added,
     * changed property values are updated, and properties marked for deletion are
     * removed. NOTE: support for multiple values not currently implemented.
     *
     * @param node the current persisted node
     * @param properties the new properties
     * @return the modified node
     */
    public Node updateProperties(Node node, List<NodeProperty> properties)
    {
        log.debug("updateProperties: " + node.getUri().getPath() + ", " + node.getClass().getSimpleName());
        expectPersistentNode(node);

        try
        {
            startTransaction();

            Node ret = doUpdateProperties(node, properties);

            commitTransaction();

            return ret;
        }
        catch (Throwable t)
        {
            log.error("Update rollback for node: " + node.getUri().getPath(), t);
            if (transactionStatus != null)
                try { rollbackTransaction(); }
                catch(Throwable oops) { log.error("failed to rollback transaction", oops); }
            throw new RuntimeException("failed to update properties:  " + node.getUri().getPath(), t);
        }
        finally
        {
            if (transactionStatus != null)
                try
                {
                    log.warn("updateProperties - BUG - transaction still open in finally... calling rollback");
                    rollbackTransaction();
                }
                catch(Throwable oops) { log.error("failed to rollback transaction in finally", oops); }
        }
    }

    private Node doUpdateProperties(Node node, List<NodeProperty> properties)
    {
        NodeID nodeID = (NodeID) node.appData;

        // Iterate through the user properties and the db properties,
        // potentially updating, deleting or adding new ones
        List<PropertyStatementCreator> updates = new ArrayList<PropertyStatementCreator>();
        for (NodeProperty prop : properties)
        {
            boolean propTable = usePropertyTable(prop.getPropertyURI());
            NodeProperty cur = node.findProperty(prop.getPropertyURI());
            // Does this property exist already?
            log.debug("updateProperties: " + prop + " vs." + cur);

            if (cur != null)
            {
                if (prop.isMarkedForDeletion())
                {
                    if (propTable)
                    {
                        log.debug("doUpdateNode " + prop.getPropertyURI() + " to be deleted NodeProperty");
                        PropertyStatementCreator ppsc = new PropertyStatementCreator(nodeSchema, nodeID, prop);
                        updates.add(ppsc);
                    }
                    else
                    {
                        log.debug("doUpdateNode " + prop.getPropertyURI() + " to be deleted in Node");
                    }
                    node.getProperties().remove(prop);
                }
                else // update
                {
                    String currentValue = cur.getPropertyValue();
                    log.debug("doUpdateNode " + prop.getPropertyURI() + ": " + currentValue + " != " + prop.getPropertyValue());
                    if (!currentValue.equals(prop.getPropertyValue()))
                    {
                        if (propTable)
                        {
                            log.debug("doUpdateNode " + prop.getPropertyURI() + " to be updated in NodeProperty");
                            PropertyStatementCreator ppsc = new PropertyStatementCreator(nodeSchema, nodeID, prop, true);
                            updates.add(ppsc);
                        }
                        else
                        {
                            log.debug("doUpdateNode " + prop.getPropertyURI() + " to be updated in Node");
                        }
                        cur.setValue(prop.getPropertyValue());
                    }
                    else
                    {
                        log.debug("Not updating node property: " + prop.getPropertyURI());
                    }
                }
            }
            else
            {
                if (propTable)
                {
                    log.debug("doUpdateNode " + prop.getPropertyURI() + " to be inserted into NodeProperty");
                    PropertyStatementCreator ppsc = new PropertyStatementCreator(nodeSchema, nodeID, prop);
                    updates.add(ppsc);
                }
                else
                {
                    log.debug("doUpdateNode " + prop.getPropertyURI() + " to be inserted into Node");
                }
                node.getProperties().add(prop);
            }
        }
        // OK: update Node, then NodeProperty(s)
        NodePutStatementCreator npsc = new NodePutStatementCreator(nodeSchema, true);
        npsc.setValues(node, null);
        jdbc.update(npsc);

        for (PropertyStatementCreator psc : updates)
            jdbc.update(psc);

        return node;
    }

    /**
     * Move the node to inside the destination container.
     *
     * @param src The node to move
     * @param dest The container in which to move the node.
     * @param subject The new owner for the moved node.
     */
    public void move(Node src, ContainerNode dest, Subject subject)
    {
        log.debug("move: " + src.getUri() + " to " + dest.getUri() + " as " + src.getName());
        expectPersistentNode(src);
        expectPersistentNode(dest);
        
        // move rule checking
        if (src instanceof ContainerNode)
        {
            // check that we are not moving root or a root container
            if (src.getParent() == null || src.getParent().getUri().isRoot())
                throw new IllegalArgumentException("Cannot move a root container.");
            
            // check that 'src' is not in the path of 'dest' so that
            // circular paths are not created
            Node target = dest;
            Long srcNodeID = getNodeID(src);
            Long targetNodeID = null;
            while (target != null && !target.getUri().isRoot())
            {
                targetNodeID = getNodeID(target);
                if (targetNodeID.equals(srcNodeID))
                    throw new IllegalArgumentException("Cannot move to a contained sub-node.");
                target = target.getParent();
            }
        }
        
        try
        {    
            Object newOwnerObject = identManager.toOwner(subject);
            long delta = getContentLength(src);

            startTransaction();
            
            // decrement nodeSize(s) above src
            LinkedList<Node> nodes = Node.getNodeList(src.getParent());
            Iterator<Node> i = nodes.descendingIterator();
            while ( i.hasNext() )
            {
                Node n = i.next();
                String sql = getUpdateNodeSizeSQL(n, -1*delta);
                log.debug(sql);
                jdbc.update(sql);
            }
            // increment node sizes at dest
            nodes = Node.getNodeList(dest);
            i = nodes.descendingIterator();
            while ( i.hasNext() )
            {
                Node n = i.next();
                String sql = getUpdateNodeSizeSQL(n, delta);
                log.debug(sql);
                jdbc.update(sql);
            }

            // re-parent the node
            src.setParent(dest);
            
            NodePutStatementCreator putStatementCreator = new NodePutStatementCreator(nodeSchema, true);
            
            // update the node with the new parent and potentially new name.
            putStatementCreator.setValues(src, null);
            jdbc.update(putStatementCreator);
            
            // change the ownership recursively
            chownInternal(src, newOwnerObject, true);
            
            commitTransaction();
        }
        catch (Throwable t)
        {
            log.error("move rollback for node: " + src.getUri().getPath(), t);
            if (transactionStatus != null)
                try { rollbackTransaction(); }
                catch(Throwable oops) { log.error("failed to rollback transaction", oops); }
            throw new RuntimeException("failed to move:  " + src.getUri().getPath(), t);
        }
        finally
        {
            if (transactionStatus != null)
            {
                try
                {
                    log.warn("move - BUG - transaction still open in finally... calling rollback");
                    rollbackTransaction();
                }
                catch(Throwable oops) { log.error("failed to rollback transaction in finally", oops); }
            }
        }
    }
    

    /**
     * Copy the node to the new path.
     *
     * @param node
     * @param destPath
     * @throws UnsupportedOperationException Until implementation is complete.
     */
    public void copy(Node src, ContainerNode dest)
    {
        log.debug("copy: " + src.getUri() + " to " + dest.getUri() + " as " + src.getName());
        throw new UnsupportedOperationException("Copy not implemented.");
    }
    
    /**
     * Change the ownership of the node.
     * 
     * @param node
     * @param newOwner
     * @param recursive
     */
    public void chown(Node node, Subject newOwner, boolean recursive)
    {
        log.debug("chown: " + node.getUri().getPath() + ", " + newOwner + ", " + recursive);
        expectPersistentNode(node);
        try
        {
            Object newOwnerObj = identManager.toOwner(newOwner);
            startTransaction();
            chownInternal(node, newOwnerObj, recursive);
            commitTransaction();
        }
        catch (Throwable t)
        {
            log.error("chown rollback for node: " + node.getUri().getPath(), t);
            if (transactionStatus != null)
                try { rollbackTransaction(); }
                catch(Throwable oops) { log.error("failed to rollback transaction", oops); }
            throw new RuntimeException("failed to chown:  " + node.getUri().getPath(), t);
        }
        finally
        {
            if (transactionStatus != null)
            {
                try
                {
                    log.warn("chown - BUG - transaction still open in finally... calling rollback");
                    rollbackTransaction();
                }
                catch(Throwable oops) { log.error("failed to rollback transaction in finally", oops); }
            }
        }
    }
    
    /**
     * Internal chown implementation that is not wrapped in a transaction.
     * 
     * @param node
     * @param subject
     * @param recursive
     */
    private void chownInternal(Node node, Object newOwnerObject, boolean recursive)
    {
        NodePutStatementCreator putStatementCreator = new NodePutStatementCreator(nodeSchema, true);
        
        // update the node with the specfied owner.
        putStatementCreator.setValues(node, newOwnerObject);
        jdbc.update(putStatementCreator);
        
        if (recursive && (node instanceof ContainerNode))
        {
            chownInternalRecursive((ContainerNode) node, newOwnerObject);
        }
    }
    
    /**
     * Recursively change the ownership on the given container.
     * 
     * @param container
     * @param newOwnerObj
     */
    private void chownInternalRecursive(ContainerNode container, Object newOwnerObj)
    {
        String sql = null;
        sql = getSelectNodesByParentSQL(container, CHILD_BATCH_SIZE, false);
        NodePutStatementCreator putStatementCreator = new NodePutStatementCreator(nodeSchema, true);
        NodeMapper mapper = new NodeMapper(authority, container.getUri().getPath());
        List<Node> children = jdbc.query(sql, new Object[0], mapper);
        Object[] args = new Object[1];
        while (children.size() > 0)
        {
            Node cur = null;
            for (Node child : children)
            {
                cur = child;
                child.setParent(container);
                putStatementCreator.setValues(child, newOwnerObj);
                jdbc.update(putStatementCreator);
                if (child instanceof ContainerNode)
                {
                    chownInternalRecursive((ContainerNode) child, newOwnerObj);
                }
            }
            sql = getSelectNodesByParentSQL(container,CHILD_BATCH_SIZE, true);
            args[0] = cur.getName();
            children = jdbc.query(sql, args, mapper);
            children.remove(cur); // the query is name >= cur and we already processed cur
        }

    }
    
    /**
     * Extract the internal nodeID of the provided node from the appData field.
     * @param node
     * @return a nodeID or null for a non-persisted node
     */
    protected static Long getNodeID(Node node)
    {
        if (node == null || node.appData == null)
        {
            return null;
        }
        if (node.appData instanceof NodeID)
        {
            return ((NodeID) node.appData).getID();
        }
        return null;
    }

    protected long getContentLength(Node node)
    {
        String str = node.getPropertyValue(VOS.PROPERTY_URI_CONTENTLENGTH);
        if (str != null)
            return Long.parseLong(str);
        return 0;
    }

    /**
     * The resulting SQL must use a PreparedStatement with one argument
     * (child node name). The ResultSet can be processsed with a NodeMapper.
     *
     * @param parent
     * @return SQL prepared statement string
     */
    protected String getSelectChildNodeSQL(ContainerNode parent)
    {
         StringBuilder sb = new StringBuilder();
        sb.append("SELECT nodeID");
        for (String col : NODE_COLUMNS)
        {
            sb.append(",");
            sb.append(col);
        }
        sb.append(" FROM ");
        sb.append(getNodeTableName());
        Long nid = getNodeID(parent);
        if (nid != null)
        {
            sb.append(" WHERE parentID = ");
            sb.append(getNodeID(parent));
        }
        else
            sb.append(" WHERE parentID IS NULL");
        sb.append(" AND name = ?");
        return sb.toString();
    }
    
    /**
     * The resulting SQL is a simple select statement. The ResultSet can be
     * processsed with a NodeMapper.
     *
     * @param parent The node to query for.
     * @param limit
     * @param withStart
     * @return simple SQL statement select for use with NodeMapper
     */
    protected String getSelectNodesByParentSQL(ContainerNode parent, Integer limit, boolean withStart)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT nodeID");
        for (String col : NODE_COLUMNS)
        {
            sb.append(",");
            sb.append(col);
        }
        sb.append(" FROM ");
        sb.append(getNodeTableName());
        Long nid = getNodeID(parent);
        if (nid != null)
        {
            sb.append(" WHERE parentID = ");
            sb.append(getNodeID(parent));
        }
        else
            sb.append(" WHERE parentID IS NULL");
        if (withStart)
            sb.append(" AND name >= ?");

        if (withStart || limit != null)
            sb.append(" ORDER BY name");

        if (limit != null)
        {

            if (nodeSchema.limitWithTop) // TOP, eg sybase
                sb.replace(0, 6, "SELECT TOP " + limit);
            else // LIMIT, eg postgresql
                sb.append(" LIMIT " + limit);
        }
        return sb.toString();
    }
    
    /**
     * The resulting SQL is a simple select statement. The ResultSet can be
     * processsed with a NodePropertyMapper.
     * 
     * @param node the node for which properties are queried
     * @return simple SQL string
     */
    protected String getSelectNodePropertiesByID(Node node)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT nodePropertyID, propertyURI, propertyValue FROM ");
        sb.append(getNodePropertyTableName());
        sb.append(" WHERE nodeID = ");
        sb.append(getNodeID(node));
        return sb.toString();
    }
    
    /**
     * @param node The node to delete
     * @return The SQL string for deleting the node from the database.
     */
    protected String getDeleteNodeSQL(Node node)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("DELETE FROM ");
        sb.append(getNodeTableName());
        sb.append(" WHERE nodeID = ");
        sb.append(getNodeID(node));
        return sb.toString();
    }
    
    /**
     * @param node Delete the properties of this node.
     * @return The SQL string for performing property deletion.
     */
    protected String getDeleteNodePropertiesSQL(Node node)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("DELETE FROM ");
        sb.append(getNodePropertyTableName());
        sb.append(" WHERE nodeID = ");
        sb.append(getNodeID(node));
        return sb.toString();
    }

    protected String getSetBusyStateSQL(DataNode node, NodeBusyState curState, NodeBusyState newState)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ");
        sb.append(getNodeTableName());
        sb.append(" SET busyState='");
        sb.append(newState.getValue());
        sb.append("', lastModified='");
        // always tweak the date
        Date now = new Date();
        setPropertyValue(node, VOS.PROPERTY_URI_DATE, dateFormat.format(now), true);
        Timestamp ts = new Timestamp(now.getTime());
        sb.append(dateFormat.format(now));
        sb.append("'");
        sb.append(" WHERE nodeID = ");
        sb.append(getNodeID(node));
        sb.append(" AND busyState='");
        sb.append(curState.getValue());
        sb.append("'");
        return sb.toString();
    }
    
    protected String getUpdateNodeSizeSQL(Node node, long difference)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ");
        sb.append(getNodeTableName());
        sb.append(" SET nodeSize = (");
        sb.append("nodeSize + ");
        sb.append(Long.toString(difference));
        sb.append(") WHERE nodeID = ");
        sb.append(getNodeID(node));
        return sb.toString();
    }
    
    protected String getMoveNodeSQL(Node src, ContainerNode dest, String name)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ");
        sb.append(getNodeTableName());
        sb.append(" SET parentID = ");
        sb.append(getNodeID(dest));
        sb.append(", name = '");
        sb.append(name);
        sb.append("' WHERE nodeID = ");
        sb.append(getNodeID(src));
        return sb.toString();
    }

    private static String getNodeType(Node node)
    {
        if (node instanceof DataNode)
            return NODE_TYPE_DATA;
        if (node instanceof ContainerNode)
            return NODE_TYPE_CONTAINER;
        throw new UnsupportedOperationException("unable to persist node type: " + node.getClass().getName());

    }
    private static String getBusyState(Node node)
    {
        if (node instanceof DataNode)
        {
            DataNode dn = (DataNode) node;
            if (dn.getBusy() != null)
                return dn.getBusy().getValue();
        }
        return VOS.NodeBusyState.notBusy.getValue();
    }
   
    private static void setPropertyValue(Node node, String uri, String value, boolean readOnly)
    {
        NodeProperty cur = node.findProperty(uri);
        if (cur == null)
        {
            cur = new NodeProperty(uri, value);
            node.getProperties().add(cur);
        }
        else
            cur.setValue(value);
        cur.setReadOnly(readOnly);
    }

    private static Set<String> coreProps;
    private boolean usePropertyTable(String uri)
    {
        if (coreProps == null)
        {
            // lazy init of the static set: thread-safe enough by
            // doing the assignment to the static last
            Set<String> core = new TreeSet<String>(new CaseInsensitiveStringComparator());

            core.add(VOS.PROPERTY_URI_ISPUBLIC);

            // note: very important that the node owner (creator property) is here
            core.add(VOS.PROPERTY_URI_CREATOR);

            core.add(VOS.PROPERTY_URI_CONTENTLENGTH);
            core.add(VOS.PROPERTY_URI_TYPE);
            core.add(VOS.PROPERTY_URI_CONTENTENCODING);
            core.add(VOS.PROPERTY_URI_CONTENTMD5);

            core.add(VOS.PROPERTY_URI_DATE);
            core.add(VOS.PROPERTY_URI_GROUPREAD);
            core.add(VOS.PROPERTY_URI_GROUPWRITE);
            coreProps = core;
        }
        return !coreProps.contains(uri);
    }

    private class PropertyStatementCreator implements PreparedStatementCreator
    {
        private NodeSchema ns;
        private boolean update;

        private NodeID nodeID;
        private NodeProperty prop;

        public PropertyStatementCreator(NodeSchema ns, NodeID nodeID, NodeProperty prop)
        {
            this(ns, nodeID, prop, false);
        }
        
        public PropertyStatementCreator(NodeSchema ns, NodeID nodeID, NodeProperty prop, boolean update)
        {
            this.ns = ns;
            this.nodeID = nodeID;
            this.prop = prop;
            this.update = update;
        }

        // if we care about caching the statement, we should look into prepared 
        // statement caching by the driver
        @Override
        public PreparedStatement createPreparedStatement(Connection conn) throws SQLException
        {
            String sql;
            PreparedStatement prep;
            if (prop.isMarkedForDeletion())
                sql = getDeleteSQL();
            else if (update)
                sql = getUpdateSQL();
            else
                sql = getInsertSQL();
            log.debug(sql);
            prep = conn.prepareStatement(sql);
            setValues(prep);
            return prep;
        }

        void setValues(PreparedStatement ps)
            throws SQLException
        {
            int col = 1;
            if (prop.isMarkedForDeletion())
            {
                ps.setLong(col++, nodeID.getID());
                ps.setString(col++, prop.getPropertyURI());
                log.debug("setValues: " + nodeID.getID() + "," + prop.getPropertyURI());
            }
            else if (update)
            {
                ps.setString(col++, prop.getPropertyValue());
                ps.setLong(col++, nodeID.getID());
                ps.setString(col++, prop.getPropertyURI());
                log.debug("setValues: " + prop.getPropertyValue() + "," + nodeID.getID() + "," + prop.getPropertyURI());
            }
            else
            {
                ps.setLong(col++, nodeID.getID());
                ps.setString(col++, prop.getPropertyURI());
                ps.setString(col++, prop.getPropertyValue());
                log.debug("setValues: " + nodeID.getID() + "," + prop.getPropertyURI() + "," + prop.getPropertyValue());
            }
        }

        public String getSQL()
        {
            if (update)
                return getUpdateSQL();
            return getInsertSQL();
        }

        private String getInsertSQL()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("INSERT INTO ");
            sb.append(ns.propertyTable);
            sb.append(" (nodeID,propertyURI,propertyValue) VALUES (?, ?, ?)");
            return sb.toString();
        }
        private String getUpdateSQL()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("UPDATE ");
            sb.append(ns.propertyTable);
            sb.append(" SET propertyValue = ?");
            sb.append(" WHERE nodeID = ?");
            sb.append(" AND propertyURI = ?");
            return sb.toString();
        }
        private String getDeleteSQL()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("DELETE FROM ");
            sb.append(ns.propertyTable);
            sb.append(" WHERE nodeID = ?");
            sb.append(" AND propertyURI = ?");
            return sb.toString();
        }
    }

    private class NodePutStatementCreator implements PreparedStatementCreator
    {
        private NodeSchema ns;
        private boolean update;

        private Node node = null;
        private Object differentOwner = null;

        public NodePutStatementCreator(NodeSchema ns, boolean update)
        {
            this.ns = ns;
            this.update = update;
        }

        // if we care about caching the statement, we should look into prepared
        // statement caching by the driver
        @Override
        public PreparedStatement createPreparedStatement(Connection conn) throws SQLException
        {
            PreparedStatement prep;
            if (update)
            {
                String sql = getUpdateSQL();
                log.debug(sql);
                prep = conn.prepareStatement(sql);
            }
            else
            {
                String sql = getInsertSQL();
                log.debug(sql);
                prep = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            }
            setValues(prep);
            return prep;
        }

        public void setValues(Node node, Object differentOwner)
        {
            this.node = node;
            this.differentOwner = differentOwner;
        }
        
        void setValues(PreparedStatement ps)
            throws SQLException
        {
            StringBuilder sb = new StringBuilder();

            int col = 1;

            if (node.getParent() != null)
            {
                long v = getNodeID(node.getParent());
                ps.setLong(col, v);
                sb.append(v);
            }
            else
            {
                ps.setNull(col, Types.BIGINT);
                sb.append("null");
            }
            col++;
            sb.append(",");
            
            String name = node.getName();
            ps.setString(col++, name);
            sb.append(name);
            sb.append(",");

            ps.setString(col++, getNodeType(node));
            sb.append(getNodeType(node));
            sb.append(",");

            ps.setString(col++, getBusyState(node));
            sb.append(getBusyState(node));
            sb.append(",");

            ps.setBoolean(col++, node.isPublic());
            setPropertyValue(node, VOS.PROPERTY_URI_ISPUBLIC, Boolean.toString(node.isPublic()), false);
            sb.append(node.isPublic());
            sb.append(",");

            String pval;
            
            // ownerID and creatorID data type
            int ownerDataType = identManager.getOwnerType();

            Object ownerObject = null;
            NodeID nodeID = (NodeID) node.appData;
            ownerObject = nodeID.ownerObject;
            if (differentOwner != null)
                ownerObject = differentOwner;
            if (ownerObject == null)
                throw new IllegalStateException("cannot update a node without an owner.");
            
            // ownerID
            ps.setObject(col++, ownerObject, ownerDataType);
            sb.append(ownerObject);
            sb.append(",");
            
            // always use the value in nodeID.ownerObject
            ps.setObject(col++, nodeID.ownerObject, ownerDataType);
            sb.append(nodeID.ownerObject);
            sb.append(",");

            //log.debug("setValues: " + sb);

            pval = node.getPropertyValue(VOS.PROPERTY_URI_GROUPREAD);
            if (pval != null)
                ps.setString(col, pval);
            else
                ps.setNull(col, Types.VARCHAR);
            col++;
            sb.append(pval);
            sb.append(",");

            //log.debug("setValues: " + sb);

            pval = node.getPropertyValue(VOS.PROPERTY_URI_GROUPWRITE);
            if (pval != null)
                ps.setString(col, pval);
            else
                ps.setNull(col, Types.VARCHAR);
            col++;
            sb.append(pval);
            sb.append(",");
            
            //log.debug("setValues: " + sb);

            // always tweak the date
            Date now = new Date();
            setPropertyValue(node, VOS.PROPERTY_URI_DATE, dateFormat.format(now), true);
            //java.sql.Date dval = new java.sql.Date(now.getTime());
            Timestamp ts = new Timestamp(now.getTime());
            ps.setTimestamp(col, ts, cal);
            col++;
            sb.append(dateFormat.format(now));
            sb.append(",");

            //log.debug("setValues: " + sb);

            pval = node.getPropertyValue(VOS.PROPERTY_URI_TYPE);
            if (pval != null)
                ps.setString(col, pval);
            else
                ps.setNull(col, Types.VARCHAR);
            col++;
            sb.append(pval);
            sb.append(",");

            pval = node.getPropertyValue(VOS.PROPERTY_URI_CONTENTENCODING);
            if (pval != null)
                ps.setString(col, pval);
            else
                ps.setNull(col, Types.VARCHAR);
            col++;
            sb.append(pval);
            sb.append(",");

            // nodeSize contains content-length for all node types
            pval = node.getPropertyValue(VOS.PROPERTY_URI_CONTENTLENGTH);
            if (pval != null)
            {
                ps.setLong(col, Long.valueOf(pval));
                sb.append(pval);
            }
            else
            {
                ps.setNull(col, Types.BIGINT);
                sb.append("null");
            }
            col++;
            sb.append(",");

            // contentLength and contentMD5 are for DataNode(s) only
            if (ns.fileMetadataWritable)
            {
                pval = node.getPropertyValue(VOS.PROPERTY_URI_CONTENTLENGTH);
                if (pval != null && node instanceof DataNode)
                {
                    ps.setLong(col, Long.valueOf(pval));
                    sb.append(pval);
                }
                else
                {
                    ps.setNull(col, Types.BIGINT);
                    sb.append("null");
                }
                col++;
                sb.append(",");

                pval = node.getPropertyValue(VOS.PROPERTY_URI_CONTENTMD5);
                if (pval != null && node instanceof DataNode)
                    ps.setBytes(col, HexUtil.toBytes(pval));
                else
                    ps.setNull(col, Types.VARBINARY);
                col++;
                sb.append(pval);
                sb.append(",");

                //log.debug("setValues: " + sb);
            }
            
            if (update)
            {
                ps.setLong(col, getNodeID(node));
                sb.append(",");
                sb.append(getNodeID(node));
            }
            
            log.debug("setValues: " + sb);
        }

        private String getInsertSQL()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("INSERT INTO ");
            sb.append(ns.nodeTable);
            sb.append(" (");

            int numCols = NODE_COLUMNS.length;
            if ( !ns.fileMetadataWritable )
                numCols = numCols - 2;

            for (int c=0; c<numCols; c++)
            {
                if (c > 0)
                    sb.append(",");
                sb.append(NODE_COLUMNS[c]);
            }
            sb.append(") VALUES (");
            for (int c=0; c<numCols; c++)
            {
                if (c > 0)
                    sb.append(",");
                sb.append("?");
            }
            sb.append(")");
            return sb.toString();
        }
        private String getUpdateSQL()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("UPDATE ");
            sb.append(ns.nodeTable);

            int numCols = NODE_COLUMNS.length;
            if ( !ns.fileMetadataWritable )
                numCols = numCols - 2;

            sb.append(" SET ");
            for (int c=0; c<numCols; c++)
            {
                if (c > 0)
                    sb.append(",");
                sb.append(NODE_COLUMNS[c]);
                sb.append(" = ?");
            }
            sb.append(" WHERE nodeID = ? AND busyState = '");
            sb.append(NodeBusyState.notBusy.getValue());
            sb.append("'");
            
            return sb.toString();
        }
    }

    private class NodePathStatementCreator implements PreparedStatementCreator
    {
        private String[] path;
        private String nodeTablename;
        private String propTableName;

        public NodePathStatementCreator(String[] path, String nodeTablename, String propTableName)
        {
            this.path = path;
            this.nodeTablename = nodeTablename;
            this.propTableName = propTableName;
        }

        public PreparedStatement createPreparedStatement(Connection conn) throws SQLException
        {
            String sql = getSQL();
            log.debug("SQL: " + sql);

            PreparedStatement ret = conn.prepareStatement(sql);
            for (int i=0; i<path.length; i++)
                ret.setString(i+1, path[i]);
            return ret;
        }

        String getSQL()
        {
            StringBuilder sb = new StringBuilder();
            String acur = null;
            sb.append("SELECT ");
            for (int i=0; i<path.length; i++)
            {
                acur = "a"+i;
                if (i > 0)
                    sb.append(",");
                for (int c=0; c<NODE_COLUMNS.length; c++)
                {
                    if (c > 0)
                        sb.append(",");
                    sb.append(acur);
                    sb.append(".");
                    sb.append(NODE_COLUMNS[c]);
                }
                sb.append(",");
                sb.append(acur);
                sb.append(".nodeID");
            }

            sb.append(" FROM ");
            String aprev;
            for (int i=0; i<path.length; i++)
            {
                aprev = acur;
                acur = "a"+i;
                if (i > 0)
                    sb.append(" JOIN ");
                sb.append(nodeTablename);
                sb.append(" AS ");
                sb.append(acur);
                if (i > 0)
                {
                    sb.append(" ON ");
                    sb.append(aprev);
                    sb.append(".nodeID=");
                    sb.append(acur);
                    sb.append(".parentID");
                }
            }

            sb.append(" WHERE ");
            for (int i=0; i<path.length; i++)
            {
                acur = "a"+i;
                if (i == 0) // root
                {
                    sb.append(acur);
                    sb.append(".parentID IS NULL");
                }
                sb.append(" AND ");
                sb.append(acur);
                sb.append(".name = ?");
            }
            return sb.toString();
        }
    }

    private class NodePathExtractor implements ResultSetExtractor
    {
        private int columnsPerNode;

        public NodePathExtractor()
        {
            this.columnsPerNode = NODE_COLUMNS.length + 1;
        }

        public Object extractData(ResultSet rs)
            throws SQLException, DataAccessException
        {
            Node ret = null;
            ContainerNode root = null;
            String curPath = "";
            int numColumns = rs.getMetaData().getColumnCount();

            while ( rs.next() )
            {
                if (root == null) // reading first row completely
                {
                    log.debug("reading path from row 1");
                    int col = 1;
                    ContainerNode cur = null;
                    while (col < numColumns)
                    {
                        log.debug("readNode at " + col + ", path="+curPath);
                        Node n = readNode(rs, col, curPath);
                        log.debug("readNode: " + n.getUri());
                        curPath = n.getUri().getPath();
                        col += columnsPerNode;
                        ret = n; // always return the last node
                        if (root == null) // root container
                        {
                            cur = (ContainerNode) n;
                            root = cur;
                        }
                        else if (col < numColumns) // container in the path
                        {
                            ContainerNode cn = (ContainerNode) n;
                            cur.getNodes().add(cn);
                            cn.setParent(cur);
                            cur = cn;
                        }
                        else // last data node
                        {
                            cur.getNodes().add(n);
                            n.setParent(cur);
                        }
                    }
                }
                else
                    log.warn("found extra rows, expected only 0 or 1");
            }
            return ret;
        }

        private Node readNode(ResultSet rs, int col, String basePath)
            throws SQLException
        {
            Long parentID = null;
            Object o = rs.getObject(col++);
            if (o != null)
            {
                Number n = (Number) o;
                parentID = new Long(n.longValue());
            }
            String name = rs.getString(col++);
            String type = rs.getString(col++);
            String busyString = getString(rs, col++);
            boolean isPublic = rs.getBoolean(col++);

            Object ownerObject = rs.getObject(col++);
            String owner = null;

            Object creatorObject = rs.getObject(col++); // unused
            
            String groupRead = getString(rs, col++);
            String groupWrite = getString(rs, col++);

            Date lastModified = rs.getTimestamp(col++, cal);

            String contentType = getString(rs, col++);
            String contentEncoding = getString(rs, col++);

            Long nodeSize = null;
            o = rs.getObject(col++);
            if (o != null)
            {
                Number n = (Number) o;
                nodeSize = new Long(n.longValue());
            }
            Long contentLength = null;
            o = rs.getObject(col++);
            if (o != null)
            {
                Number n = (Number) o;
                contentLength = new Long(n.longValue());
            }
            log.debug("readNode: nodeSize = " + nodeSize + ", contentLength = " + contentLength);

            Object contentMD5 = rs.getObject(col++);

            Long nodeID = null;
            o = rs.getObject(col++);
            if (o != null)
            {
                Number n = (Number) o;
                nodeID = new Long(n.longValue());
            }
            
            String path = basePath + "/" + name;
            VOSURI vos;
            try { vos = new VOSURI(new URI("vos", authority, path, null, null)); }
            catch(URISyntaxException bug)
            {
                throw new RuntimeException("BUG - failed to create vos URI", bug);
            }

            Node node;
            if (NODE_TYPE_CONTAINER.equals(type))
            {
                node = new ContainerNode(vos);
            }
            else if (NODE_TYPE_DATA.equals(type))
            {
                node = new DataNode(vos);
                ((DataNode) node).setBusy(NodeBusyState.getStateFromValue(busyString));
            }
            else
            {
                throw new IllegalStateException("Unknown node database type: " + type);
            }

            NodeID nid = new NodeID();
            nid.id = nodeID;
            nid.ownerObject = ownerObject;
            node.appData = nid;

            if (contentType != null)
            {
                node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_TYPE, contentType));
            }

            if (contentEncoding != null)
            {
                node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_CONTENTENCODING, contentEncoding));
            }
            
            if (node instanceof DataNode && contentLength != null)
            {
                node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_CONTENTLENGTH, contentLength.toString()));
            }
            else if (node instanceof ContainerNode && nodeSize != null)
            {
                node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_CONTENTLENGTH, nodeSize.toString()));
            }

            if (contentMD5 != null && contentMD5 instanceof byte[])
            {
                byte[] md5 = (byte[]) contentMD5;
                if (md5.length < 16)
                {
                    byte[] tmp = md5;
                    md5 = new byte[16];
                    System.arraycopy(tmp, 0, md5, 0, tmp.length);
                    // extra space is init with 0
                }
                String contentMD5String = HexUtil.toHex(md5);
                node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_CONTENTMD5, contentMD5String));
            }
            if (lastModified != null)
            {
                node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_DATE, dateFormat.format(lastModified)));
            }
            if (groupRead != null)
            {
                node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_GROUPREAD, groupRead));
            }
            if (groupWrite != null)
            {
                node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_GROUPWRITE, groupWrite));
            }
            if (owner != null)
            {
                node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_CREATOR, owner));
            }
            node.getProperties().add(new NodeProperty(VOS.PROPERTY_URI_ISPUBLIC, Boolean.toString(isPublic)));

            // set the read-only flag on the properties
            for (String propertyURI : VOS.READ_ONLY_PROPERTIES)
            {
                int propertyIndex = node.getProperties().indexOf(new NodeProperty(propertyURI, null));
                if (propertyIndex != -1)
                {
                    node.getProperties().get(propertyIndex).setReadOnly(true);
                }
            }

            return node;
        }

        private String getString(ResultSet rs, int col)
            throws SQLException
        {
            String ret = rs.getString(col);
            if (ret != null)
            {
                ret = ret.trim();
                if (ret.length() == 0)
                ret = null;
            }
            return ret;
        }
    }
}
