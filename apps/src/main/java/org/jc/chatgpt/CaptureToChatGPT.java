package org.jc.chatgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.xlogistx.api.gpt.GPTAPI;
import io.xlogistx.http.NIOHTTPServer;
import io.xlogistx.http.NIOHTTPServerCreator;
import io.xlogistx.widget.LedWidget;
import io.xlogistx.widget.WidgetUtil;
import net.sourceforge.tess4j.TesseractException;
import okhttp3.OkHttpClient;
import org.jc.imaging.ocr.OCRUtil;
import org.zoxweb.server.http.HTTPAPICaller;
import org.zoxweb.server.http.OkHTTPCall;
import org.zoxweb.server.io.IOUtil;
import org.zoxweb.server.io.UByteArrayOutputStream;
import org.zoxweb.server.logging.LogWrapper;
import org.zoxweb.server.task.TaskUtil;
import org.zoxweb.server.util.DateUtil;
import org.zoxweb.server.util.GSONUtil;
import org.zoxweb.shared.annotation.EndPointProp;
import org.zoxweb.shared.annotation.MappedProp;
import org.zoxweb.shared.filters.StringFilter;
import org.zoxweb.shared.http.*;
import org.zoxweb.shared.util.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Date;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;




@MappedProp(name = "chat-gpt", id = "chat-gpt-capture")
public class CaptureToChatGPT extends JFrame {

    public static final LogWrapper log = new LogWrapper(CaptureToChatGPT.class).setEnabled(true);
    private JButton startButton;
    private JButton stopButton;
    private JButton clearPromptButton;
    private JButton selectButton;
    private JButton manualButton;
    private JTextField refreshRateField;
    private FilterPromptPanel filterPromptPanel;
    private JTextArea resultTextArea;
    private JFileChooser fileChooser;
    private JButton fileChooserButton;
    private JTextField modelName;
    private JCheckBox autoCopyToClipboardCB;

    private GPTSelection gptSelection;
    private BufferedImage lastCapture;

    private LedWidget activityLed;

    private final HTTPAPICaller gptAPI = GPTAPI.SINGLETON.create(null);


    private final ReentrantLock lock = new ReentrantLock();

    private final OkHttpClient httpClient = OkHTTPCall.createOkHttpBuilder(null, null, 120, true, 10, 120).build();


    static private Rectangle selectedArea;
    private RateCounter rc = new RateCounter("app");

    //private AtomicBoolean isRunning = new AtomicBoolean(false);

    // API keys (replace with your actual keys)
    private static String ocrApiKey = null; // Replace with your OCR.space API key
    //private static String openAIApiKey = null; // Replace with your OpenAI API key
    //private static String openAIApiURL = null;
    private static String openAIModel = null;
    private Future<?> future = null;
    public CaptureToChatGPT() {
        setTitle("Screen OCR ChatGPT Application");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);
    }


    private JPanel createWorkPanel()
    {
        // Create the panel with GridBagLayout
        JPanel panel = new JPanel(new GridBagLayout());

        // Create GridBagConstraints
        GridBagConstraints gbc = new GridBagConstraints();

        // Labels
        JLabel resultLabel = new JLabel("GPT Result");
        JLabel promptLabel = new JLabel("GPT Prompt");

        // TextAreas
        resultTextArea = new JTextArea();
        filterPromptPanel = new FilterPromptPanel();
        filterPromptPanel.setPromptInputText("Analyse the image and respond with solution only");

        // Make TextAreas wrap lines and scrollable
        resultTextArea.setLineWrap(true);
        resultTextArea.setWrapStyleWord(true);

//        promptTextArea.setLineWrap(true);
//        promptTextArea.setWrapStyleWord(true);

        JScrollPane resultScrollPane = new JScrollPane(resultTextArea);
        JScrollPane promptScrollPane = new JScrollPane(filterPromptPanel);

        // Set preferred sizes for initial appearance
        resultScrollPane.setPreferredSize(new Dimension(250, 150));
        promptScrollPane.setPreferredSize(new Dimension(250, 150));

        // Insets for padding
        Insets padding = new Insets(5, 5, 5, 5);

        // Add labels to the first row
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = padding;
        gbc.weightx = 0.5; // Equal horizontal space for labels
        gbc.weighty = 0;
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(resultLabel, gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        panel.add(promptLabel, gbc);

        // Add TextAreas to the second row
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = padding;
        gbc.weightx = 0.5;
        gbc.weighty = 1.0; // Allows vertical expansion
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(resultScrollPane, gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        panel.add(promptScrollPane, gbc);

        return panel;
    }
    private void initComponents() {
        // Panel for controls
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        activityLed = new LedWidget(30, 30, Color.BLACK);
        activityLed.mapStatus(Const.Bool.ON, Color.GREEN)
                        .mapStatus(Const.Bool.OFF, Color.RED);
        activityLed.setStatus(Const.Bool.ON);
        manualButton = new JButton("Manual");
        startButton = new JButton("Start");
        stopButton = new JButton("Stop");
        clearPromptButton = new JButton("Clear Prompt");
        selectButton = new JButton("Select");
        gptSelection = new GPTSelection(this);
        autoCopyToClipboardCB  = new JCheckBox("AutoCopy");
        gptSelection.selectionBox.setName("CONF");

        refreshRateField = new JTextField("10s", 5); // Default refresh rate is 5 seconds
        fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        fileChooserButton = new JButton("Files");
        modelName = new JTextField(10);
        modelName.setText(openAIModel);
        //controlPanel.add(selectButton);
        controlPanel.add(manualButton);
        controlPanel.add(autoCopyToClipboardCB);
        controlPanel.add(activityLed);
        controlPanel.add(startButton);
        controlPanel.add(stopButton);
        controlPanel.add(clearPromptButton);
        controlPanel.add(new JLabel("Refresh Rate (s):"));
        controlPanel.add(refreshRateField);

        controlPanel.add(new JLabel("CONF"));
        controlPanel.add(gptSelection.selectionBox);

//        controlPanel.add(new JLabel("Filename"));
        controlPanel.add(fileChooserButton);
        controlPanel.add(new JLabel("Model"));
        controlPanel.add(modelName);

        fileChooserButton.addActionListener(e->fileChooser.showOpenDialog(this));



        // Add panels to the frame
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(controlPanel, BorderLayout.NORTH);
        getContentPane().add(createWorkPanel(), BorderLayout.CENTER);

        // Button actions
        selectButton.addActionListener(e -> selectProcessing());
        startButton.addActionListener(e -> startProcessing());
        manualButton.addActionListener(e->{
            try {
                TaskUtil.defaultTaskScheduler().queue(200, ()-> {
                    try {
                        processChatGPT();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                });
            }
            catch (Exception ex)
            {
                ex.printStackTrace();
            }
        });
        clearPromptButton.addActionListener(e -> filterPromptPanel.setPromptInputText(""));


//        startButton.addMouseListener(new MouseAdapter() {
//            @Override
//            public void mouseReleased(MouseEvent e)
//            {
//                startProcessing();
//
//            }
//        });

        stopButton.addActionListener(e -> stopProcessing());

        // Initially, stop button is disabled
        stopButton.setEnabled(false);

        gptAPI.updateOkHttpClient(httpClient);

    }

    @EndPointProp(methods = {HTTPMethod.GET}, name="to-chat-gpt", uris="/capture-to-gpt")
    public NVGenericMap captureToGPT() throws TesseractException, IOException, AWTException {

        if (!lock.isLocked())
        {
            return processChatGPT();
        }

        return new NVGenericMap().build("system", "busy");
    }


    private NVGenericMap processChatGPT() throws AWTException, IOException, TesseractException
    {
        NVGenericMap request = null;
        NVGenericMap response = null;
        StringFilter sf = null;
        {
            try
            {
                if(SUS.isNotEmpty(filterPromptPanel.getFilterInputText()))
                {
                    NVGenericMap config = GSONUtil.fromJSONDefault(filterPromptPanel.getFilterInputText(), NVGenericMap.class);
                    sf = new StringFilter("toto", config);
                }


            } catch (Exception e) {
                e.printStackTrace();
                sf = null;
            }
        }
        try {
            lock.lock();
            SwingUtilities.invokeLater(() -> activityLed.setStatus(Const.Bool.OFF));
            manualButton.setEnabled(false);
            String prompt = null;
            // Capture the selected screen area
            BufferedImage image = WidgetUtil.captureSelectedArea(selectedArea);


            rc.start();
            if (WidgetUtil.compareImages(image, lastCapture)) {
                lastCapture = image;
                rc.stop();
                if (log.isEnabled())
                    log.getLogger().info("Image compare equal, it took: " + Const.TimeInMillis.toString(rc.lastDeltaInMillis()));
                return null;
            } else {
                lastCapture = image;
            }
            File chosenDir = fileChooser.getSelectedFile();
            String baseFileName = null;

            if (chosenDir != null) {
                if(chosenDir.isDirectory()) {
                    baseFileName = DateUtil.FILE_DATE_FORMAT.format(new Date());
                    File file = new File(chosenDir, baseFileName+".png");
                    ImageIO.write(image, "png", file);
                    log.getLogger().info("ext: " + SharedStringUtil.valueAfterRightToken(file.getName(), ".") +
                            " filename: " + file);
                }
            }

            if (log.isEnabled())
                log.getLogger().info("New image to process, it took: " + Const.TimeInMillis.toString(rc.lastDeltaInMillis()));
            String text = null;

            NVGenericMap selectionInfo = gptSelection.getSelectionInfo();
            if (selectionInfo != null) {
                switch (selectionInfo.getName()) {
                    case "local-ocr":
                        text = OCRUtil.SINGLETON.tesseractOCRImage(selectionInfo.getValue("path"), selectionInfo.getValue("language"), image);
                        break;

                    case "remote-ocr":
                        // Perform OCR
                        text = performOCRWithOCRSpace(selectionInfo.getValue("image-format"), image, selectionInfo.getValue("api-key"));
                        break;
                    case "gpt-api-key":
                        text = filterPromptPanel.getPromptInputText();
                        break;
                }
                prompt = text;
                String textToDisplay = prompt;
                SwingUtilities.invokeLater(() -> filterPromptPanel.setPromptInputText(textToDisplay));
            } else {
                prompt = filterPromptPanel.getPromptInputText();
            }


            if (SUS.isNotEmpty(prompt))
            {
                UByteArrayOutputStream baos = new UByteArrayOutputStream();
                ImageIO.write(image, "png", baos);
                String[] models = modelName.getText().split(",");

                Object content = null;
                for (int i = 0; i < models.length; i++) {
                    if (content == null)
                        request = GPTAPI.SINGLETON.toVisionParams(models[i], prompt, 5000, baos, "png");
                    else
                        request = GPTAPI.SINGLETON.toPromptParams(models[i], "" + content, 5000);


                    //response = GSONUtil.fromJSONDefault(rd.getDataAsString(), NVGenericMap.class);
                    gptAPI.setHTTPAuthorization(new HTTPAuthorization(HTTPAuthScheme.BEARER, gptSelection.getGPTAPIKey()));
                    response = gptAPI.syncCall(GPTAPI.Command.COMPLETION, request);
                    if (log.isEnabled()) log.getLogger().info("" + response);
                    NVGenericMapList choices = (NVGenericMapList) response.get("choices");


                    if (log.isEnabled()) log.getLogger().info("" + choices);
                    NVGenericMap firstChoice = choices.getValue().get(0);
                    if (log.isEnabled()) log.getLogger().info("" + firstChoice);
                    NVGenericMap message = (NVGenericMap) firstChoice.get("message");

                    content = message.getValue("content");
                    if (log.isEnabled()) log.getLogger().info("Content\n" + content);

                    SwingUtilities.invokeLater(() -> resultTextArea.setText("" + message.getValue("content")));
                }


                    if(autoCopyToClipboardCB.isSelected()) {

                        String toClipboard = null;
                        if(sf != null && content instanceof String)
                        {
                            toClipboard = sf.decode((String) content);
                        }
                        else if (content instanceof String)
                            toClipboard = (String) content;

                        WidgetUtil.copyToClipboard(SUS.isNotEmpty(toClipboard) ?  toClipboard : ""+content);
                        if(baseFileName != null)
                        {
                            File file = new File(chosenDir, baseFileName + "." + sf.getExtension());
                            try(OutputStream os = Files.newOutputStream(file.toPath()))
                            {
                                os.write(toClipboard.getBytes());
                            }
                        }
                    }


                //log.getLogger().info("api call duration " + Const.TimeInMillis.toString(rd.getDuration()));
            }
        }
        finally {
            SwingUtilities.invokeLater(()-> activityLed.setStatus(Const.Bool.ON));
            manualButton.setEnabled(true);
            lock.unlock();
        }



        return response;
    }





    private void startProcessing() {
        if(future != null)
            future.cancel(true);



        // Disable start button and enable stop button
        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        // Parse refresh rate
        int refreshRate;
        try {
            refreshRate = (int)Const.TimeInMillis.toMillis(refreshRateField.getText()); //// Convert to milliseconds
            if (log.isEnabled()) log.getLogger().info("refreshRate: " + refreshRate);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Invalid refresh rate. Please enter a number.", "Error", JOptionPane.ERROR_MESSAGE);
            stopProcessing();
            return;
        }

        future = TaskUtil.defaultTaskScheduler().scheduleAtFixedRate(()->{try
        {
            processChatGPT();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        }, 0, refreshRate, TimeUnit.MILLISECONDS);
    }

    private void stopProcessing() {
        // Enable start button and disable stop button
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        this.setEnabled(true);

        // Stop the timer
        if (future != null) {
            future.cancel(true);
            lastCapture = null;
        }
    }

    private void selectProcessing()
    {
        this.setVisible(false);
        try {
            selectedArea = WidgetUtil.captureSelectedArea();
            if (log.isEnabled()) log.getLogger().info("SelectedArea: " + selectedArea);
        } catch (Exception e) {
           e.printStackTrace();
        }
        finally {
            this.setVisible(true);
        }

    }




    // Function to perform OCR using OCR.space API (same as before)
    public static String performOCRWithOCRSpace(String imageFormat, BufferedImage image, String apiKey) {
        try {
            String urlStr = "https://api.ocr.space/parse/image";
            URL url = new URL(urlStr);

            // Prepare the image file
            UByteArrayOutputStream ubaos = new UByteArrayOutputStream();
            ImageIO.write(image, "png", ubaos);
            String imageName = "Capture."+imageFormat;

            if (log.isEnabled()) log.getLogger().info("image size: " + ubaos.size());


            // Setup the connection
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("apikey", apiKey);
            conn.setDoOutput(true);

            // Prepare the multipart request
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            OutputStream outputStream = conn.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"));

            // Add image file
            writer.write("--" + boundary + "\r\n");
            writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"" + imageName+ "\"\r\n");
            writer.write("Content-Type: image/"+imageFormat + "\r\n\r\n");
            writer.flush();
            outputStream.write(ubaos.getInternalBuffer(),0, ubaos.size());
            outputStream.flush();
            writer.write("\r\n");

            // Add language parameter (optional)
            writer.write("--" + boundary + "\r\n");
            writer.write("Content-Disposition: form-data; name=\"language\"\r\n\r\n");
            writer.write("eng\r\n");

            // Close the multipart request
            writer.write("--" + boundary + "--\r\n");
            writer.flush();
            writer.close();

            // Get the response
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    (conn.getInputStream())));

            StringBuilder response = new StringBuilder();
            String output;

            while ((output = br.readLine()) != null) {
                response.append(output);
            }

            conn.disconnect();

            // Parse the OCR result
            return parseOCRSpaceResult(response.toString());

        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // Function to parse OCR.space API response (same as before)
    private static String parseOCRSpaceResult(String jsonResponse) {
        JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
        JsonArray parsedResults = jsonObject.getAsJsonArray("ParsedResults");
        if (parsedResults != null && parsedResults.size() > 0) {
            JsonObject parsedResult = parsedResults.get(0).getAsJsonObject();
            String extractedText = parsedResult.get("ParsedText").getAsString();
            return extractedText.trim();
        }
        return "";
    }

  

    // Main method
    public static void main(String ...args) {

        try
        {
            ParamUtil.ParamMap params = ParamUtil.parse("=", args);

            boolean selectArea = params.booleanValue("cap", true);
            ocrApiKey = params.stringValue("ocr-key", true);
            String openAIApiKey = params.stringValue("gpt-key", true);
            //openAIApiURL = params.stringValue("gpt-url", true);
            openAIModel = params.stringValue("gpt-model", true);
            String jsonFilterFile = params.stringValue("json-filter", true);
            String filterContent = null;
            if (jsonFilterFile != null) {
                try {
                    filterContent = IOUtil.inputStreamToString(IOUtil.locateFile(jsonFilterFile));
                    log.getLogger().info("Filter-Content\n" + filterContent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            String webServerConfig = params.stringValue("web-config", true);


            if (selectArea) {
                try {
                    selectedArea = WidgetUtil.captureSelectedArea();
                    if (log.isEnabled()) log.getLogger().info("SelectedArea: " + selectedArea);
                } catch (AWTException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }


            CaptureToChatGPT app = null;
            if (webServerConfig != null) {
                try {

                    NIOHTTPServerCreator httpServerCreator = new NIOHTTPServerCreator();
                    File file = IOUtil.locateFile(webServerConfig);
                    HTTPServerConfig hsc = GSONUtil.fromJSON(IOUtil.inputStreamToString(file), HTTPServerConfig.class);
                    if (log.isEnabled()) log.getLogger().info("" + hsc);
                    if (log.isEnabled()) log.getLogger().info("" + hsc.getConnectionConfigs());
                    httpServerCreator.setAppConfig(hsc);
                    NIOHTTPServer ws = httpServerCreator.createApp();
                    app = ws.getEndPointsManager().lookupBean("chat-gpt");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }


            if (app == null)
                app = new CaptureToChatGPT();


            // for java lambda compliance
            CaptureToChatGPT appConst = app;
            String filterContentConst = filterContent;
            //-----------------------------------------

            SwingUtilities.invokeLater(() -> {
                appConst.initComponents();
                if (SUS.isNotEmpty(filterContentConst)) {
                    appConst.filterPromptPanel.setFilterInputText(filterContentConst);
                }
                appConst.setVisible(true);

                appConst.gptSelection.setGPTAPIKey(openAIApiKey);
            });

        } catch (Exception e) {
            e.printStackTrace();

            System.err.println("Usage: cap=[on/off] gpt-key=[oai-api-key] gpt-url=[vision-url] gpt-model=[oai-model] json-filter=[data-filter-file]");
        }
    }
}
