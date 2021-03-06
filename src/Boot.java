import java.awt.AWTException;
import java.awt.FontFormatException;
import java.awt.GridLayout;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.util.HashMap;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.UnsupportedLookAndFeelException;

import org.pcap4j.core.NotOpenException;
import org.pcap4j.core.PcapAddress;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.PcapNetworkInterface.PromiscuousMode;
import org.pcap4j.core.Pcaps;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.namednumber.IpNumber;

public class Boot {
	public static Double version = 1.22;
	private static InetAddress addr = null;
	public static PcapHandle handle = null;

	public static void main(String[] args) throws UnsupportedLookAndFeelException, AWTException, ClassNotFoundException, FontFormatException{
		try {
			if(!Sanity.check()){
				System.exit(1);
			}
			Settings.init();
			Settings.set("autoload", Settings.get("autoload", "0")); //"autoload" is an ini-only toggle for advanced users.
			setupTray();

			getLocalAddr();
			PcapNetworkInterface nif = Pcaps.getDevByAddress(addr);
			if(nif == null){
				JOptionPane.showMessageDialog(null, "The device you selected doesn't seem to exist. Double-check the IP you entered.", "Error", JOptionPane.ERROR_MESSAGE);
			}

			final int snapLen = 65536;
			final PromiscuousMode mode = PromiscuousMode.PROMISCUOUS;
			final int timeout = 0;
			handle = nif.openLive(snapLen, mode, timeout);

			HashMap<String, Integer> nonact = new HashMap<String, Integer>();
			HashMap<String, Timestamp> active = new HashMap<String, Timestamp>();
			Overlay ui = new Overlay();

			while(true){				
				Packet packet = handle.getNextPacket(); 

				if(packet != null){
					IpV4Packet ippacket = packet.get(IpV4Packet.class);

					if(ippacket != null){
						if(ippacket.getHeader().getProtocol() == IpNumber.UDP){
							UdpPacket udppack = ippacket.get(UdpPacket.class);
							String srcAddrStr = ippacket.getHeader().getSrcAddr().toString(); // Shows as '/0.0.0.0'

							if(udppack != null){
								if(ui.getMode()){
									if(ui.numSurvs() > 4){ //Fixes people affected by a loading bug being stuck in list
										ui.clearSurvs();
										active.clear();
									}
								}
								if(active.containsKey(srcAddrStr) && !ippacket.getHeader().getSrcAddr().isSiteLocalAddress()){
									if(active.get(srcAddrStr) != null && udppack.getPayload().getRawData().length == 68  //Packets are STUN related: 56 is request, 68 is response
											&& ippacket.getHeader().getDstAddr().isSiteLocalAddress()){
										if(ui.getMode()){ //KILLER MODE
											ui.setSurvPing(srcAddrStr, handle.getTimestamp().getTime() - active.get(srcAddrStr).getTime());
										}else{ //SURVIVOR MODE
											ui.setKillerPing(handle.getTimestamp().getTime() - active.get(srcAddrStr).getTime());
										}

										active.put(srcAddrStr, null); //No longer expect ping
									}
								}else{
									if(udppack.getPayload().getRawData().length == 56 && ippacket.getHeader().getSrcAddr().isSiteLocalAddress()){
										active.put(ippacket.getHeader().getDstAddr().toString(), handle.getTimestamp());
									}
									else if(udppack.getPayload().getRawData().length == 68 && !ippacket.getHeader().getSrcAddr().isSiteLocalAddress()){
										if(nonact.containsKey(srcAddrStr)){
											nonact.put(srcAddrStr, nonact.get(srcAddrStr) + 1);
										}else{
											nonact.put(srcAddrStr, 1);
										}

										if(nonact.containsKey(srcAddrStr) && nonact.get(srcAddrStr) == 4){ //The new packet is sent multiple times, we only really need 4 to confirm
											active.put(srcAddrStr, null); //This serves to prevent seeing the message upon joining then leaving
											nonact.remove(srcAddrStr);
										}
									}else if(udppack.getPayload().getRawData().length == 4 && ippacket.getHeader().getSrcAddr().isSiteLocalAddress()){
										String payload = ippacket.toHexString().replaceAll(" ", "").substring(ippacket.toHexString().replaceAll(" ", "").length() - 8);
										if(payload.equals("beefface")){ //BEEFFACE occurs on disconnect from lobby
											active.remove(ippacket.getHeader().getDstAddr().toString());
											if(ui.getMode()){
												ui.removeSurv(ippacket.getHeader().getDstAddr().toString());
											}else{
												ui.setKillerPing(0);
											}
										}
									}
								}
							}
						}
					}
				}
			}
		} catch (PcapNativeException | NotOpenException | InstantiationException | IllegalAccessException
				| IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static void setupTray() throws AWTException{
		final SystemTray tray = SystemTray.getSystemTray();

		ActionListener listener = new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				handle.close();
				System.exit(1);
			}
		};
		final PopupMenu popup = new PopupMenu();
		final MenuItem exit = new MenuItem();
		exit.addActionListener(listener);
		exit.setLabel("Exit");
		popup.add(exit);
		final TrayIcon trayIcon = new TrayIcon(Toolkit.getDefaultToolkit().getImage(ClassLoader.getSystemResource("resources/icon.png")), "MLGA", popup);
		tray.add(trayIcon);
	}

	public static void getLocalAddr() throws InterruptedException, PcapNativeException, UnknownHostException{
		if(Settings.getDouble("autoload", 0) == 1){
			addr = InetAddress.getByName(Settings.get("addr", ""));
			return;
		}
		final JFrame frame = new JFrame("MLGA Network Device Locate");
		frame.setFocusableWindowState(true);

		final JLabel ipLab = new JLabel("Select LAN IP obtained from Network Settings:", JLabel.LEFT);
		final JComboBox<String> lanIP = new JComboBox<String>();
		final JLabel lanLabel = new JLabel("If your device IP isn't in the dropdown, provide it below.");
		final JTextField lanText = new JTextField(Settings.get("addr", ""));

		for(PcapNetworkInterface i : Pcaps.findAllDevs()){
			for(PcapAddress x : i.getAddresses()){
				if(x.getAddress() != null && x.getNetmask() != null){
					System.out.println("Found: "+ x.getAddress().getHostAddress());
					lanIP.addItem(x.getAddress().getHostAddress());
				}
			}
		}

		if(lanIP.getItemCount() == 0){
			lanIP.addItem("No devices found. Try running in Admin mode.");
		}

		final JButton start = new JButton("Start");
		start.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent e){
				try {
					if(lanText.getText().length() >= 7){ // 7 is because the minimum field is 0.0.0.0
						addr = InetAddress.getByName(lanText.getText());
						System.out.println("Using IP from textfield: "+ lanText.getText());
					}else{
						addr = InetAddress.getByName((String)lanIP.getSelectedItem());
						System.out.println("Using IP from dropdown: "+ (String)lanIP.getSelectedItem());
					}
					Settings.set("addr", addr.getHostAddress().replaceAll("/", ""));
					frame.setVisible(false);
					frame.dispose();
				} catch (UnknownHostException e1) {
					e1.printStackTrace();
				}
			}
		});

		frame.setLayout(new GridLayout(5,1));
		frame.add(ipLab);
		frame.add(lanIP);
		frame.add(lanLabel);
		frame.add(lanText);
		frame.add(start);
		frame.setAlwaysOnTop(true);
		frame.pack();
		frame.setLocation(5, 420);
		frame.setSize(400, 175);
		frame.setVisible(true);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		while(frame.isVisible()){
			Thread.sleep(10);
		}
	}
}
