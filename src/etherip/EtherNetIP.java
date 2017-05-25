/*******************************************************************************
 * Copyright (c) 2012 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package etherip;

import static etherip.protocol.Encapsulation.Command.SendRRData;
import static etherip.protocol.Encapsulation.Command.UnRegisterSession;
import static etherip.types.CNPath.Identity;
import static etherip.types.CNPath.MessageRouter;
import static etherip.types.CNService.Get_Attribute_Single;

import java.util.logging.Level;
import java.util.logging.Logger;

import etherip.protocol.CIPMultiRequestProtocol;
import etherip.protocol.Connection;
import etherip.protocol.Encapsulation;
import etherip.protocol.GetShortAttributeProtocol;
import etherip.protocol.GetStringAttributeProtocol;
import etherip.protocol.ListServices;
import etherip.protocol.ListServicesProtocol.Service;
import etherip.protocol.MRChipReadProtocol;
import etherip.protocol.MRChipWriteProtocol;
import etherip.protocol.MessageRouterProtocol;
import etherip.protocol.ProtocolAdapter;
import etherip.protocol.RegisterSession;
import etherip.protocol.SendRRDataProtocol;
import etherip.protocol.UnconnectedSendProtocol;
import etherip.types.CIPData;
import etherip.types.CNService;

/** API for communicating via EtherNet/IP
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class EtherNetIP implements AutoCloseable
{
    final public static String version = "1.2";

	final public static Logger logger = Logger.getLogger(EtherNetIP.class.getName());

	final private String address;
	final private int slot;
	private Connection connection = null;

	private DeviceInfo device_info;

	/** Initialize
	 *  @param address IP address of device
	 */
	public EtherNetIP(final String address, final int slot)
	{
		this.address = address;
		this.slot = slot;
	}

	/** Connect to device, register session, obtain basic info
	 *  @throws Exception on error
	 */
	public void connect() throws Exception
	{
		connection = new Connection(address, slot);
		listServices();
		registerSession();
		getDeviceInfo();
	}

	/** List supported services
	 *
	 *  <p>Queries PLC for supported services.
	 *  Logs them and checks for the required "Communications" service.
	 *
	 *  @throws Exception on error getting services, or when expected service not supported
	 */
    private void listServices() throws Exception
    {
        final ListServices list_services = new ListServices();
		connection.execute(list_services);

		final Service[] services = list_services.getServices();
		if (services == null  ||  services.length < 1)
			throw new Exception("Device does not support EtherIP services");
		logger.log(Level.FINE, "Service: {0}", services[0].getName());
		if (! services[0].getName().toLowerCase().startsWith("comm"))
				throw new Exception("Expected EtherIP communication service, got " + services[0].getName());
    }

	/** Register session
	 *  @throws Exception on error
	 */
	private void registerSession() throws Exception
	{
		final RegisterSession register = new RegisterSession();
		connection.execute(register);
		connection.setSession(register.getSession());
	}

	/** Obtain device info
	 *  @throws Exception on error
	 */
	private void getDeviceInfo() throws Exception
    {
		final short vendor = getShortAttribute(1);
		final short device_type = getShortAttribute(2);
		final short revision = getShortAttribute(4);
		final short serial = getShortAttribute(6);
		final String name = getStringAttribute(7);
		device_info = new DeviceInfo(vendor, device_type, revision, serial, name);
		logger.log(Level.INFO, "{0}", device_info);
    }

	/** Helper for reading a 'short' typed attribute
	 *  @param attr Attribute to read from {@link Identity}
	 *  @return value of attribute
	 *  @throws Exception on error
	 */
	private short getShortAttribute(final int attr) throws Exception
    {
		final GetShortAttributeProtocol attr_proto;
		final Encapsulation encap =
			new Encapsulation(SendRRData, connection.getSession(),
				new SendRRDataProtocol(
					new MessageRouterProtocol(Get_Attribute_Single, Identity().attr(attr),
					    (attr_proto = new GetShortAttributeProtocol()))));
		connection.execute(encap);
	    return attr_proto.getValue();
    }

	/** Helper for reading a 'String' typed attribute
	 *  @param attr Attribute to read from {@link Identity}
	 *  @return value of attribute
	 *  @throws Exception on error
	 */
	private String getStringAttribute(final int attr) throws Exception
    {
		final GetStringAttributeProtocol attr_proto;
		final Encapsulation encap =
				new Encapsulation(SendRRData, connection.getSession(),
					new SendRRDataProtocol(
						new MessageRouterProtocol(Get_Attribute_Single, Identity().attr(attr),
						    (attr_proto = new GetStringAttributeProtocol()))));
		connection.execute(encap);
	    return attr_proto.getValue();
    }

	/** Read a single scalar tag
	 *  @param tag Name of tag
	 *  @return Current value of the tag
	 *  @throws Exception on error
	 */
	public CIPData readTag(final String tag) throws Exception
	{
		return readTag(tag, (short) 1);
	}

    /** Read a single array tag
     *  @param tag Name of tag
     *  @param count Number of array elements to read
     *  @return Current value of the tag
     *  @throws Exception on error
     */
	public CIPData readTag(final String tag, short count) throws Exception
	{
		final MRChipReadProtocol cip_read = new MRChipReadProtocol(tag, count);
		final Encapsulation encap =
			new Encapsulation(SendRRData, connection.getSession(),
				new SendRRDataProtocol(
					new UnconnectedSendProtocol(slot,
					    cip_read)));
		connection.execute(encap);

		return cip_read.getData();
	}


    /** Read multiple scalar tags in one network transaction
     *  @param tags Tag names
     *  @return Current values of the tags
     *  @throws Exception on error
     */
	public CIPData[] readTags(final String... tags) throws Exception
	{
	    final MRChipReadProtocol[] reads = new MRChipReadProtocol[tags.length];
	    for (int i=0; i<reads.length; ++i)
	        reads[i] = new MRChipReadProtocol(tags[i]);

	    final Encapsulation encap =
            new Encapsulation(SendRRData, connection.getSession(),
                new SendRRDataProtocol(
                    new UnconnectedSendProtocol(slot,
                        new MessageRouterProtocol(CNService.CIP_MultiRequest, MessageRouter(),
                            new CIPMultiRequestProtocol(reads)))));
	    connection.execute(encap);

	    final CIPData[] results = new CIPData[reads.length];
        for (int i=0; i<results.length; ++i)
            results[i] = reads[i].getData();

        return results;
    }

	/** Write a tag
	 *  @param tag Tag name
	 *  @param value Value to write
	 *  @throws Exception on error
	 */
	public void writeTag(final String tag, final CIPData value) throws Exception
	{
	    final MRChipWriteProtocol cip_write = new MRChipWriteProtocol(tag, value);

	    final Encapsulation encap =
	            new Encapsulation(SendRRData, connection.getSession(),
                    new SendRRDataProtocol(
	                    new UnconnectedSendProtocol(slot,
	                            cip_write)));
        connection.execute(encap);
    }

	/** Write multiple tags in one network transaction
	 *  @param tags Tag names to write
	 *  @param values Values to write
	 *  @throws Exception on error
	 */
	public void writeTags(final String[] tags, final CIPData[] values) throws Exception
    {
	    if (tags.length != values.length)
	        throw new IllegalArgumentException("Got " + tags.length + " tags but " + values.length + " values");
        final MRChipWriteProtocol[] writes = new MRChipWriteProtocol[tags.length];
        for (int i=0; i<tags.length; ++i)
            writes[i] = new MRChipWriteProtocol(tags[i], values[i]);

        final Encapsulation encap =
                new Encapsulation(SendRRData, connection.getSession(),
                    new SendRRDataProtocol(
                        new UnconnectedSendProtocol(slot,
                                new MessageRouterProtocol(CNService.CIP_MultiRequest, MessageRouter(),
                                        new CIPMultiRequestProtocol(writes)))));
        connection.execute(encap);
    }

	/** Unregister session (device will close connection) */
	private void unregisterSession()
	{
		if (connection.getSession() == 0)
			return;
		try
		{
			connection.write(new Encapsulation(UnRegisterSession, connection.getSession(), new ProtocolAdapter()));
			// Cannot read after this point because PLC will close the connection
		}
		catch (Exception ex)
		{
			logger.log(Level.WARNING, "Error un-registering session", ex);
		}
	}

	/** Close connection to device */
	@Override
    public void close() throws Exception
	{
		if (connection != null)
		{
			unregisterSession();
			connection.close();
		}
	}

	/** @return String representation for debugging */
	@Override
    public String toString()
	{
		final StringBuilder buf = new StringBuilder();
		buf.append("EtherIP address '").append(address).append("', session 0x").append(Integer.toHexString(connection.getSession())).append("\n");
		buf.append(device_info);
		return buf.toString();
	}
}
