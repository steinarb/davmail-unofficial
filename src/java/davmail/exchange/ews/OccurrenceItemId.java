/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2010  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package davmail.exchange.ews;

import java.io.IOException;
import java.io.Writer;

public class OccurrenceItemId extends ItemId {
    protected final int instanceIndex;

    /**
     * Build Item id object from item id and change key.
     *
     * @param recurringMasterId recurring master id
     * @param instanceIndex     occurrence index
     */
    public OccurrenceItemId(String recurringMasterId, int instanceIndex) {
        super("OccurrenceItemId", recurringMasterId);
        this.instanceIndex = instanceIndex;
    }

    /**
     * Build Item id object from item id and change key.
     *
     * @param recurringMasterId recurring master id
     * @param changeKey         change key
     * @param instanceIndex     occurrence index
     */
    public OccurrenceItemId(String recurringMasterId, String changeKey, int instanceIndex) {
        super("OccurrenceItemId", recurringMasterId, changeKey);
        this.instanceIndex = instanceIndex;
    }

    /**
     * Write item id as XML.
     *
     * @param writer request writer
     * @throws IOException on error
     */
    public void write(Writer writer) throws IOException {
        writer.write("<t:");
        writer.write(name);
        writer.write(" RecurringMasterId=\"");
        writer.write(id);
        if (changeKey != null) {
            writer.write("\" ChangeKey=\"");
            writer.write(changeKey);
        }
        writer.write("\" InstanceIndex=\"");
        writer.write(String.valueOf(instanceIndex));
        writer.write("\"/>");
    }

}
