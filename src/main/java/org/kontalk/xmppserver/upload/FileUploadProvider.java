/*
 * Kontalk XMPP Tigase extension
 * Copyright (C) 2015 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.xmppserver.upload;

import tigase.conf.ConfigurationException;
import tigase.xml.Element;

import java.util.Map;


/**
 * An interface for file upload providers.
 * @author Daniele Ricci
 */
public interface FileUploadProvider {

    public void init(Map<String, Object> props) throws ConfigurationException;

    public String getName();

    // for service discovery
    public String getNode();
    public String getDescription();

    public Element getServiceInfo();

}
