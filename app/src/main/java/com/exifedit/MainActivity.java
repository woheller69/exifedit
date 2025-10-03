package com.exifedit;

import static android.os.Environment.DIRECTORY_PICTURES;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvResult;
    private File tempFile = null;
    private Context mContext = this;
    private ActivityResultLauncher<Intent> filePickerResultLauncher;
    private FloatingActionButton filePicker;
    private FloatingActionButton fileSave;
    private FloatingActionButton exifDelete;
    private FloatingActionButton exifDate;
    private FloatingActionButton exifLocation;
    private File outputFile;
    private long originalTime;
    private String originalName;
    TiffOutputSet outputSet;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ThemeUtils.setStatusBarAppearance(this);
        tvResult = findViewById(R.id.tvResult);
        filePicker = findViewById(R.id.filePicker);
        fileSave = findViewById(R.id.fileSave);
        exifDelete = findViewById(R.id.fileDelete);
        exifDate = findViewById(R.id.fileModDate);
        exifLocation = findViewById(R.id.fileModLocation);

        checkPermission();
        registerFilePickerResultLauncher();

        //Create Directory if needed
        File outputDir = new File(Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES).getPath()+"/exifEdit");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.e("File", "Failed to make directory: " + outputFile);
            return;
        }

        filePicker.setOnClickListener(view -> openFilePicker());
        fileSave.setOnClickListener(view -> saveFile());
        exifDelete.setOnClickListener(view -> deleteExifData());
        exifDate.setOnClickListener(view -> modifyExifDate());
        exifLocation.setOnClickListener(view -> modifyExifLocation());

    }


    private void modifyExifLocation() {
        if (tempFile == null) return;

        if (outputSet == null) outputSet = new TiffOutputSet();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Location");

        // Set up the input field
        final EditText input = new EditText(this);
        input.setHint("±dd.dd, ±dd.dd");
        builder.setView(input);

        // Add the buttons
        builder.setPositiveButton("OK", (dialog, which) -> {
            String inputText = input.getText().toString();
            String pattern = "^-?\\d+\\.\\d+, -?\\d+\\.\\d+$";
            if (inputText.matches(pattern)){
                try {
                    double latitude = Double.parseDouble(inputText.split(",")[0]);
                    double longitude = Double.parseDouble(inputText.split(",")[1]);
                    outputSet.setGpsInDegrees(longitude, latitude);
                    updateTempFile();
                    displayExifData();
                } catch (ImagingException e) {
                    Toast.makeText(MainActivity.this, "Invalid GPS. Please use ±dd.dd, ±dd.dd", Toast.LENGTH_LONG).show();
                    throw new RuntimeException(e);
                }
            } else {
                Toast.makeText(MainActivity.this, "Invalid GPS. Please use ±dd.dd, ±dd.dd", Toast.LENGTH_LONG).show();
            }

        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.show();

    }

    private void modifyExifDate() {
        if (tempFile == null) return;
        //Modify DATE_TIME_ORIGINAL
        if (outputSet == null) outputSet = new TiffOutputSet();
        final TiffOutputDirectory exifDirectory;
        try {
            exifDirectory = outputSet.getOrCreateExifDirectory();

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter Date and Time");

            // Set up the input field
            String inputHint = "";
            inputHint = parseExifData(0x9003);
            final EditText input = new EditText(this);
            input.setText(inputHint);
            input.setHint("YYYY:MM:DD HH:MM:SS");
            builder.setView(input);

            // Add the buttons
            builder.setPositiveButton("OK", (dialog, which) -> {
                String inputText = input.getText().toString();
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
                try {
                    Date date = dateFormat.parse(inputText);
                    String formattedDate = dateFormat.format(date);
                    originalTime = date.getTime();

                    // make sure to remove old value if present (this method will not fail if the tag does not exist).
                    if (!parseExifData(0x132).equals("")) outputSet.removeField(0x132);
                    exifDirectory.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
                    exifDirectory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, formattedDate);
                    exifDirectory.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED);
                    exifDirectory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED, formattedDate);

                    updateTempFile();
                    displayExifData();

                } catch (ParseException e) {
                    Toast.makeText(MainActivity.this, "Invalid date format. Please use yyyy:MM:dd HH:mm:ss", Toast.LENGTH_LONG).show();
                } catch (ImagingException ignored) {

                }
            });

            builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

            AlertDialog dialog = builder.create();
            dialog.show();

        } catch (ImagingException e) {
            Toast.makeText(this,"Error reading Exif data",Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteExifData() {
        if (tempFile == null) return;

        try {
            // Step 1: Read the existing tempFile into a byte array
            byte[] fileData;
            try (FileInputStream fis = new FileInputStream(tempFile)) {
                fileData = fis.readAllBytes();
            }

            // Step 2: Modify the data in memory
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ExifRewriter exifRewriter = new ExifRewriter();
            TiffOutputSet tiffOutputSet = new TiffOutputSet();
            tiffOutputSet.getOrCreateExifDirectory();
            exifRewriter.updateExifMetadataLossy(new ByteArrayInputStream(fileData), byteArrayOutputStream, tiffOutputSet);

            // Step 3: Write the modified data back to the tempFile
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(byteArrayOutputStream.toByteArray());
            }

            displayExifData();
        } catch (IOException e) {
            Toast.makeText(this,e.toString(),Toast.LENGTH_SHORT).show();
            Log.d("ExifEdit",e.toString());
        }
    }

    private void updateTempFile() {
        if (tempFile == null || outputSet == null) return;

        try {
            // Step 1: Read the existing tempFile into a byte array
            byte[] fileData;
            try (FileInputStream fis = new FileInputStream(tempFile)) {
                fileData = fis.readAllBytes();
            }

            // Step 2: Modify the data in memory
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ExifRewriter exifRewriter = new ExifRewriter();
            exifRewriter.updateExifMetadataLossy(new ByteArrayInputStream(fileData), byteArrayOutputStream, outputSet);

            // Step 3: Write the modified data back to the tempFile
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(byteArrayOutputStream.toByteArray());
            }

        } catch (IOException e) {
            Toast.makeText(this,"Error updating temp file",Toast.LENGTH_SHORT).show();
        }
    }

    private void saveFile() {
        if (tempFile == null || outputSet == null) return;
        outputFile = new File(Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES).getPath() + "/exifEdit/" + originalName);

        OutputStream os;
        try {
            os = new BufferedOutputStream(new FileOutputStream(outputFile.getAbsolutePath()));
            new ExifRewriter().updateExifMetadataLossy(tempFile, os, outputSet);  //Lossless makes exif grow and my crash app
            outputFile.setLastModified(originalTime);
            Toast.makeText(this,"->" + outputFile.getAbsolutePath(),Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this,"File in Pictures/ExifEdit exists. Delete it first!",Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {

        super.onResume();
    }
    private void registerFilePickerResultLauncher() {
        filePickerResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    tvResult.setText("");
                    outputSet = null;
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null) {
                            Uri selectedFileUri = data.getData();
                            if (selectedFileUri != null) {
                                createTempFileFromUri(selectedFileUri);
                                //Hack to get original file date and name
                                DocumentFile documentFile = DocumentFile.fromSingleUri(mContext, selectedFileUri);
                                originalTime = documentFile.lastModified();
                                originalName = documentFile.getName();
                                displayExifData();
                            }
                        }
                    }
                });
    }

    private void displayExifData() {
        if (tempFile != null) {
            TiffImageMetadata metadata;
            try {
                metadata = getExifMetadata(tempFile);
                if (metadata != null){

                    //Display all fields
                    List<TiffField> fields = metadata.getAllFields();
                    String text = "";
                    for (int i = 0; i < fields.size(); i++){
                        TiffField field = fields.get(i);
                        String value = field.getValueDescription();
                        if (value.contains("Invalid")) value = "Invalid";
                        text = text + field.getTagName() + " : " + value + "\n";
                    }
                    tvResult.setText(text);
                    outputSet = metadata.getOutputSet();
                } else {
                    Toast.makeText(this,"No Metadata",Toast.LENGTH_SHORT).show();
                }

            } catch (IOException e) {
                Toast.makeText(this,"Error reading Exif data",Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(mContext, "Unable to create temporary file", Toast.LENGTH_SHORT).show();
        }
    }

    private String parseExifData(int key) {
        if (tempFile != null) {

            TiffImageMetadata metadata;
            try {
                metadata = getExifMetadata(tempFile);
                if (metadata != null){

                    //Display all fields
                    List<TiffField> fields = metadata.getAllFields();
                    String text = "";
                    for (int i = 0; i < fields.size(); i++){
                        TiffField field = fields.get(i);
                        if (field.getTag() == key){
                            return field.getValue().toString();
                        }
                    }

                }

            } catch (IOException e) {
                Toast.makeText(this,"Error reading Exif data",Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(mContext, "Unable to create temporary file", Toast.LENGTH_SHORT).show();
        }
        return "";
    }

    private void checkPermission() {
        String manifestPermission;
        manifestPermission = Manifest.permission.READ_MEDIA_IMAGES;
        //ACCESS_MEDIA_LOCATION permission is not needed when using Storage Access Framework or Photo Picker
        int permission = ContextCompat.checkSelfPermission(this, manifestPermission);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{manifestPermission}, 0);
            Toast.makeText(this, "Missing permission", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d("Permission", "Permission is granted");
        } else {
            Log.d("Permission", "Permission is not granted");
        }
    }


    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/jpeg");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerResultLauncher.launch(intent);
    }


    private void createTempFileFromUri(Uri uri) {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                return;
            }

            tempFile = File.createTempFile("temp", ".jpg", getCacheDir());
            outputStream = new FileOutputStream(tempFile);

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            e.printStackTrace();
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            tempFile = null;
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tempFile!=null) tempFile.delete();
    }

    public TiffImageMetadata getExifMetadata(final File jpegImageFile) {

        final ImageMetadata metadata;
        try {
            metadata = Imaging.getMetadata(jpegImageFile);
            final JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
            if (null != jpegMetadata) {
                // note that exif might be null if no Exif metadata is found.
                return jpegMetadata.getExif();
            }
        } catch (IOException e) {
            Toast.makeText(this,"Error reading Exif data",Toast.LENGTH_SHORT).show();
        }
        return null;
    }
}
