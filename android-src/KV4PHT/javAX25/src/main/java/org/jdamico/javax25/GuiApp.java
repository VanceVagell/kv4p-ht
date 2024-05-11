package org.jdamico.javax25;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;

import javax.sound.sampled.Mixer;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.text.DefaultCaret;

import org.jdamico.javax25.ax25.Afsk1200Modulator;
import org.jdamico.javax25.ax25.Packet;
import org.jdamico.javax25.soundcard.Soundcard;
import org.jdamico.javax25.threads.GuiDecoderThread;

public class GuiApp extends JPanel implements ActionListener {

	private JTextArea textArea = new JTextArea(40, 80);
	private JTextField inputPacketField;
	private JTextField callsign;
	private JTextField destination;
	private JTextField digipath;
	private JLabel audioIn;
	private JLabel audioOut;
	private static JFrame frame;

	private JComboBox inputDevicesComboBox = null; 
	private JComboBox outputDevicesComboBox = null;
	private JButton decodeBtn = null;
	private JButton resetBtn = null;
	private JButton sendBtn = null;
	private JLabel audioLevelLabel = null;
	private JLabel audioLevelValue = null;
	private Thread guiDecoderThread;
	public static Soundcard sc = null;
	public static Afsk1200Modulator mod = null;

	public static void main(String[] args) {
		javax.swing.SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				createAndShowGUI();
			}
		});		
	}

	public GuiApp() {
		super(new BorderLayout());

		JPanel northPanel = new JPanel();  // **** to hold buttons
		JPanel southPanel = new JPanel();  // **** to hold buttons

		List<Mixer.Info> lst = Soundcard.getInputDevicesLst();
		String[] inputDeviceArray = new String[lst.size()];
		for (int i = 0; i < inputDeviceArray.length; i++) {
			inputDeviceArray[i] = lst.get(i).getName();
			textArea.setText(textArea.getText()+"Input: "+lst.get(i).getName()+" | "+lst.get(i).getDescription()+"\n");

		}
		audioIn = new JLabel("In:");
		northPanel.add(audioIn);
		inputDevicesComboBox = new JComboBox(inputDeviceArray);
		northPanel.add(inputDevicesComboBox);

		lst = Soundcard.getOutputDevicesLst();
		String[] outputDeviceArray = new String[lst.size()];
		for (int i = 0; i < outputDeviceArray.length; i++) {
			outputDeviceArray[i] = lst.get(i).getName();
			textArea.setText(textArea.getText()+"Output: "+lst.get(i).getName()+" | "+lst.get(i).getDescription()+"\n");

		}

		textArea.setText(textArea.getText()+"=================================================================================\n");
		audioOut = new JLabel("Out:");
		northPanel.add(audioOut);
		outputDevicesComboBox = new JComboBox(outputDeviceArray);
		northPanel.add(outputDevicesComboBox);

		decodeBtn = new JButton("Open Audio Interface");
		decodeBtn.addActionListener(this);
		northPanel.add(decodeBtn);

		resetBtn = new JButton("Reset");


		resetBtn.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent arg0) {
				Soundcard.running = false;
				guiDecoderThread.interrupt();
				inputDevicesComboBox.setEnabled(true);
				outputDevicesComboBox.setEnabled(true);
				decodeBtn.setEnabled(true);
				resetBtn.setEnabled(false);
				sendBtn.setEnabled(false);
			}
		});

		resetBtn.setEnabled(false);

		inputPacketField = new JTextField(40);
		callsign = new JTextField(6);
		digipath = new JTextField(8);
		destination = new JTextField(5);
		JLabel callsignL = new JLabel("Callsign:");
		southPanel.add(callsignL);
		southPanel.add(callsign);
		JLabel digipathL = new JLabel("Digipath:");
		southPanel.add(digipathL);
		southPanel.add(digipath);
		JLabel destL = new JLabel("Dest:");
		southPanel.add(destL);
		southPanel.add(destination);
		JLabel packetL = new JLabel("Packet:");
		southPanel.add(packetL);
		southPanel.add(inputPacketField);
		northPanel.add(resetBtn);

		Soundcard.enumerate();



		sendBtn = new JButton("Send Packet");
		sendBtn.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent arg0) {

				//PU2LVM-13 APRS @141244z2332.53S/04645.51W_131/003g004t067r000p000P000b10142h70Test ARISS,WIDE2-1
				/*
				 * Packet packet = new Packet("APRS",
						"PU2LVM",
						new String[] {"WIDE1-1", "WIDE2-2"},
						Packet.AX25_CONTROL_APRS,
						Packet.AX25_PROTOCOL_NO_LAYER_3,
						"@141244z2332.53S/04645.51W_131/003g004t067r000p000P000b10142h70Test".getBytes());
				 */

				boolean validInputs = false;
				StringBuffer sbInputPacketErrors = new StringBuffer();
				if(destination.getText() != null && destination.getText().length() > 0) validInputs = true;
				else {
					validInputs = false;
					sbInputPacketErrors.append("Invalid destination.\n");
				}
				if(callsign.getText() != null && callsign.getText().length() > 0) validInputs = true;
				else {
					validInputs = false;
					sbInputPacketErrors.append("Invalid callsign.\n");
				}
				if(digipath.getText() != null && digipath.getText().length() > 0 && !digipath.getText().contains(" ")) validInputs = true;
				else {
					validInputs = false;
					sbInputPacketErrors.append("Invalid digipath.\n");
				}
				if(inputPacketField.getText() != null && inputPacketField.getText().length() > 0) validInputs = true;
				else {
					validInputs = false;
					sbInputPacketErrors.append("Invalid packet data.\n");
				}
				if(validInputs) {
					Packet packet = new Packet(destination.getText(),
							callsign.getText(),
							digipath.getText().split(","),
							Packet.AX25_CONTROL_APRS,
							Packet.AX25_PROTOCOL_NO_LAYER_3,
							inputPacketField.getText().getBytes());

					System.out.println(packet);
					mod.prepareToTransmit(packet);
					sc.transmit();
				}else {
					JOptionPane.showMessageDialog(frame, sbInputPacketErrors.toString());
				}
			}
		});
		sendBtn.setEnabled(false);
		southPanel.add(sendBtn);

		audioLevelLabel = new JLabel("Audio Level: ");
		northPanel.add(audioLevelLabel);

		audioLevelValue = new JLabel("000");
		audioLevelValue.setForeground(Color.red);
		northPanel.add(audioLevelValue);

		add(northPanel, BorderLayout.PAGE_START);
		add(southPanel, BorderLayout.AFTER_LAST_LINE);

		DefaultCaret caret = (DefaultCaret) textArea.getCaret();
		caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);   

		JScrollPane scrollPane = new JScrollPane(textArea);

		add(scrollPane, BorderLayout.CENTER);

		setBorder(BorderFactory.createEmptyBorder(20,20,20,20));
	}

	private static void createAndShowGUI() {
		//Create and set up the window.
		frame = new JFrame(Constants.APP_NAME+" v"+Constants.APP_VERSION);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		ImageIcon img = new ImageIcon("dist/icon.png");
		frame.setIconImage(img.getImage());

		//Create and set up the content pane.
		JComponent newContentPane = new GuiApp();
		newContentPane.setOpaque(true); //content panes must be opaque
		frame.setContentPane(newContentPane);

		//Display the window.
		frame.pack();
		frame.setVisible(true);
	}

	@Override
	public void actionPerformed(ActionEvent arg0) {
		openAudioInterfaceAndDecode();

	}

	private void openAudioInterfaceAndDecode() {
		decodeBtn.setEnabled(false);
		inputDevicesComboBox.setEnabled(false);
		outputDevicesComboBox.setEnabled(false);
		resetBtn.setEnabled(true);
		String input = (String) inputDevicesComboBox.getSelectedItem();
		String output = (String) outputDevicesComboBox.getSelectedItem();
		Soundcard.jTextArea = textArea;
		Soundcard.audioLevelValue = audioLevelValue;
		sendBtn.setEnabled(true);
		guiDecoderThread = new GuiDecoderThread(input, output);
		guiDecoderThread.start();
	}

}
