/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson;

import com.google.common.primitives.Ints;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.DataHolder;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamDriver;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import hudson.diagnosis.OldDataMonitor;
import hudson.model.Descriptor;
import hudson.util.XStream2;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

/**
 * Represents an XML data file that Jenkins uses as a data file.
 *
 *
 * <h2>Evolving data format</h2>
 * <p>
 * Changing data format requires a particular care so that users with
 * the old data format can migrate to the newer data format smoothly.
 *
 * <p>
 * Adding a field is the easiest. When you read an old XML that does
 * not have any data, the newly added field is left to the VM-default
 * value (if you let XStream create the object, such as
 * {@link #read()} &mdash; which is the majority), or to the value initialized by the
 * constructor (if the object is created via {@code new} and then its
 * value filled by XStream, such as {@link #unmarshal(Object)}.)
 *
 * <p>
 * Removing a field requires that you actually leave the field with
 * {@code transient} keyword. When you read the old XML, XStream
 * will set the value to this field. But when the data is saved,
 * the field will no longer will be written back to XML.
 * (It might be possible to tweak XStream so that we can simply
 * remove fields from the class. Any help appreciated.)
 *
 * <p>
 * Changing the data structure is usually a combination of the two
 * above. You'd leave the old data store with {@code transient},
 * and then add the new data. When you are reading the old XML,
 * only the old field will be set. When you are reading the new XML,
 * only the new field will be set. You'll then need to alter the code
 * so that it will be able to correctly handle both situations,
 * and that as soon as you see data in the old field, you'll have to convert
 * that into the new data structure, so that the next {@code save} operation
 * will write the new data (otherwise you'll end up losing the data, because
 * old fields will be never written back.)
 *
 * <p>
 * You may also want to call {@link OldDataMonitor#report(UnmarshallingContext, String)}.
 * This can be done within a nested class {@code ConverterImpl} extending {@link hudson.util.XStream2.PassthruConverter}
 * in an override of {@link hudson.util.XStream2.PassthruConverter#callback}.
 *
 * <p>
 * In some limited cases (specifically when the class is the root object
 * to be read from XML, such as {@link Descriptor}), it is possible
 * to completely and drastically change the data format. See
 * {@link Descriptor#load()} for more about this technique.
 *
 * <p>
 * There's a few other possibilities, such as implementing a custom
 * {@link Converter} for XStream, or {@link XStream#alias(String, Class) registering an alias}.
 *
 * @see <a href="https://www.jenkins.io/doc/developer/persistence/">Architecture » Persistence</a>
 * @author Kohsuke Kawaguchi
 */
public final class XmlFile {
    private final XStream xs;
    private final File file;
    private final Path namespace;
    private final String table;
    private final int id;
    private final int foreignKey;
    private final boolean force;

    public static Connection getConnection(Path namespace) throws SQLException {
        Path dir = Jenkins.get().getRootDir().toPath();
        if (namespace != null) {
            dir = dir.resolve(namespace);
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("sqlite.db").toAbsolutePath());
    }

    public XmlFile(File file) {
        this(DEFAULT_XSTREAM, file);
    }

    public XmlFile(XStream xs, File file) {
        this(xs, file, true);
    }

    /**
     * @param force Whether or not to flush the page cache to the storage device with {@link
     *     FileChannel#force} (i.e., {@code fsync}} or {@code FlushFileBuffers}) before this method
     *     returns. If you set this to {@code false}, you will lose data integrity.
     * @since 2.304
     */
    public XmlFile(XStream xs, File file, boolean force) {
        this.xs = xs;
        this.file = file;
        if (file != null) {
            Path jenkinsHome = Jenkins.get().getRootDir().toPath();
            Path relative = jenkinsHome.relativize(file.toPath());
            if (relative.getName(0).toString().equals("jobs")) {
                namespace = relative.subpath(0, 2);
                relative = namespace.relativize(relative);
                if (relative.getName(0).toString().equals("builds") && Ints.tryParse(relative.getName(1).toString()) != null) {
                    if (relative.getName(2).toString().equals("workflow") && Ints.tryParse(relative.getName(3).toString().replaceAll(".xml", "")) != null) {
                        foreignKey = Integer.parseInt(relative.getName(1).toString());
                        id = Integer.parseInt(relative.getName(3).toString().replaceAll(".xml", ""));
                        relative = relative.getName(2);
                    } else {
                        foreignKey = -1;
                        id = Integer.parseInt(relative.getName(1).toString());
                        relative = relative.getName(2);
                    }
                } else {
                    foreignKey = -1;
                    id = 1;
                }
            } else {
                namespace = null;
                foreignKey = -1;
                id = 1;
            }
            // TODO sanitize table name against alphanumeric regex
            String table = relative.toString();
            if (table.endsWith(".xml")) {
                table = table.substring(0, table.lastIndexOf('.'));
            }
            this.table = table;
        } else {
            this.namespace = null;
            this.table = null;
            this.id = -1;
            this.foreignKey = -1;
        }
        this.force = force;
    }

    public File getFile() {
        LOGGER.log(Level.SEVERE, "You do not want to be doing this, just trust me", new Throwable());
        return file;
    }

    public XStream getXStream() {
        return xs;
    }

    /**
     * Loads the contents of this file into a new object.
     */
    public Object read() throws IOException {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Reading " + table);
        }
        if (!exists()) {
            throw new NoSuchFileException("Unable to read " + table);
        }
        try (Connection connection = getConnection(namespace); PreparedStatement statement = connection.prepareStatement(String.format("SELECT json FROM '%s' WHERE id = ? AND foreign_key = ?", table))) {
            statement.setInt(1, id);
            statement.setInt(2, foreignKey);
            ResultSet result = statement.executeQuery();
            if (!result.next()) {
                throw new NoSuchFileException("Unable to read " + table);
            }
            return xs.fromXML(result.getString(1));
        } catch (SQLException e) {
            throw new IOException("Unable to read " + table, e);
        }
    }

    /**
     * Loads the contents of this file into an existing object.
     *
     * @return
     *      The unmarshalled object. Usually the same as {@code o}, but would be different
     *      if the XML representation is completely new.
     */
    public Object unmarshal(Object o) throws IOException {
        return unmarshal(o, false);
    }

    /**
     * Variant of {@link #unmarshal(Object)} applying {@link XStream2#unmarshal(HierarchicalStreamReader, Object, DataHolder, boolean)}.
     * @since 2.99
     */
    public Object unmarshalNullingOut(Object o) throws IOException {
        return unmarshal(o, true);
    }

    private Object unmarshal(Object o, boolean nullOut) throws IOException {
        if (!exists()) {
            throw new NoSuchFileException("Unable to read " + table);
        }
        try (Connection connection = getConnection(namespace); PreparedStatement statement = connection.prepareStatement(String.format("SELECT json FROM '%s' WHERE id = ? AND foreign_key = ?", table))) {
            statement.setInt(1, id);
            statement.setInt(2, foreignKey);
            ResultSet result = statement.executeQuery();
            if (!result.next()) {
                throw new NoSuchFileException("Unable to read " + table);
            }
            StringReader in = new StringReader(result.getString(1));
            if (nullOut) {
                return ((XStream2) xs).unmarshal(DEFAULT_DRIVER.createReader(in), o, null, true);
            } else {
                return xs.unmarshal(DEFAULT_DRIVER.createReader(in), o);
            }
        } catch (SQLException e) {
            throw new IOException("Unable to read " + table, e);
        }
    }

    public void write(Object o) throws IOException {
        mkdirs();
        String value = xs.toXML(o);
        try (Connection connection = getConnection(namespace); PreparedStatement statement = connection.prepareStatement(String.format("INSERT INTO '%s' (id, foreign_key, json) VALUES (?, ?, ?) ON CONFLICT(id, foreign_key) DO UPDATE SET json = ?", table))) {
            statement.setInt(1, id);
            statement.setInt(2, foreignKey);
            statement.setString(3, value);
            statement.setString(4, value);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    /**
     * Provides an XStream replacement for an object unless a call to {@link #write} is currently in progress.
     * As per JENKINS-45892 this may be used by any class which expects to be written at top level to an XML file
     * but which cannot safely be serialized as a nested object (for example, because it expects some {@code onLoad} hook):
     * implement a {@code writeReplace} method delegating to this method.
     * The replacement need not be {@link Serializable} since it is only necessary for use from XStream.
     * @param o an object ({@code this} from {@code writeReplace})
     * @param replacement a supplier of a safely serializable replacement object with a {@code readResolve} method
     * @return {@code o}, if {@link #write} is being called on it, else the replacement
     * @since 2.74
     */
    public static Object replaceIfNotAtTopLevel(Object o, Supplier<Object> replacement) {
        return o;
    }

    public boolean exists() {
        try (Connection connection = getConnection(namespace)) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(name) FROM sqlite_master WHERE type = ? AND name = ?")) {
                statement.setString(1, "table");
                statement.setString(2, table);
                ResultSet result = statement.executeQuery();
                if (result.getInt(1) == 0) {
                    return false;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(String.format("SELECT COUNT(*) FROM '%s' WHERE id = ? AND foreign_key = ?", table))) {
                statement.setInt(1, id);
                statement.setInt(2, foreignKey);
                ResultSet result = statement.executeQuery();
                return result.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void delete() throws IOException {
        try (Connection connection = getConnection(namespace); PreparedStatement statement = connection.prepareStatement(String.format("DELETE FROM '%s' WHERE ID = ? AND foreign_key = ?", table))) {
            statement.setInt(1, id);
            statement.setInt(2, foreignKey);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    public void mkdirs() throws IOException {
        try (Connection connection = getConnection(namespace); Statement statement = connection.createStatement()) {
            statement.executeUpdate(String.format("CREATE TABLE IF NOT EXISTS '%s' (id INTEGER NOT NULL, foreign_key INTEGER NOT NULL, json STRING NOT NULL, PRIMARY KEY (id, foreign_key))", table));
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public String toString() {
        return table;
    }

    /**
     * Opens a {@link Reader} that loads XML.
     * This method uses {@link #sniffEncoding() the right encoding},
     * not just the system default encoding.
     * @return Reader for the file. should be close externally once read.
     * @throws IOException Encoding issues
     */
    public Reader readRaw() throws IOException {
        if (!exists()) {
            throw new NoSuchFileException("Unable to read " + table);
        }
        try (Connection connection = getConnection(namespace); PreparedStatement statement = connection.prepareStatement(String.format("SELECT json FROM '%s' WHERE id = ? AND foreign_key = ?", table))) {
            statement.setInt(1, id);
            statement.setInt(2, foreignKey);
            ResultSet result = statement.executeQuery();
            if (!result.next()) {
                throw new NoSuchFileException("Unable to read " + table);
            }
            return new StringReader(result.getString(1));
        } catch (SQLException e) {
            throw new IOException("Unable to read " + table, e);
        }
    }

    /**
     * Returns the XML file read as a string.
     */
    public String asString() throws IOException {
        StringWriter w = new StringWriter();
        writeRawTo(w);
        return w.toString();
    }

    /**
     * Writes the raw XML to the given {@link Writer}.
     * Writer will not be closed by the implementation.
     */
    public void writeRawTo(Writer w) throws IOException {
        try (Reader r = readRaw()) {
            IOUtils.copy(r, w);
        }
    }

    /**
     * Parses the beginning of the file and determines the encoding.
     *
     * @return
     *      always non-null.
     * @throws IOException
     *      if failed to detect encoding.
     */
    public String sniffEncoding() throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * {@link XStream} instance is supposed to be thread-safe.
     */

    private static final Logger LOGGER = Logger.getLogger(XmlFile.class.getName());

    private static final HierarchicalStreamDriver DEFAULT_DRIVER = XStream2.getDefaultDriver();

    private static final XStream DEFAULT_XSTREAM = new XStream2(DEFAULT_DRIVER);
}
