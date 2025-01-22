package com.exifedit;

import static android.os.Environment.DIRECTORY_PICTURES;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvResult;
    private File tempFile = null;
    private Context mContext = this;
    private ActivityResultLauncher<Intent> filePickerResultLauncher;
    private FloatingActionButton filePicker;
    private File outputFile;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvResult = findViewById(R.id.tvResult);
        filePicker = findViewById(R.id.filePicker);
        checkPermission();
        registerFilePickerResultLauncher();

        //Create Directory if needed
        File outputDir = new File(Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES).getPath()+"/exifEdit");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.e("File", "Failed to make directory: " + outputFile);
            return;
        }

        filePicker.setOnClickListener(view -> openFilePicker());

    }

    @Override
    protected void onResume() {

        super.onResume();
    }
    private void registerFilePickerResultLauncher() {
        filePickerResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null) {
                            Uri selectedFileUri = data.getData();
                            if (selectedFileUri != null) {
                                File tempFile = createTempFileFromUri(selectedFileUri);

                                //Hack to get original file date and name
                                DocumentFile documentFile = DocumentFile.fromSingleUri(mContext, selectedFileUri);
                                long originalDate = documentFile.lastModified();
                                String fileName = documentFile.getName();

                                if (tempFile != null) {
                                    WriteExifMetadataExample example = new WriteExifMetadataExample();

                                    TiffImageMetadata metadata = null;
                                    try {
                                        metadata = example.getExifMetadata(tempFile);
                                        if (metadata != null){

                                            //Display all fields
                                            List<TiffField> fields = metadata.getAllFields();
                                            String text = "";
                                            for (int i = 0; i < fields.size(); i++){
                                                TiffField field = fields.get(i);
                                                text = text + field.getTagName() + " : " + field.getValue().toString() + "\n";
                                            }
                                            tvResult.setText(text);

                                            //Modify DATE_TIME_ORIGINAL
                                            TiffOutputSet outputSet = metadata.getOutputSet();
                                            final TiffOutputDirectory exifDirectory = outputSet.getOrCreateExifDirectory();
                                            // make sure to remove old value if present (this method will not fail if the tag does not exist).
                                            exifDirectory.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
                                            // Create a Calendar instance and set the specific date and time
                                            Calendar calendar = Calendar.getInstance();
                                            calendar.set(2023, Calendar.JANUARY, 1, 12, 34, 56);

                                            // Get the date and time from the Calendar
                                            Date specificDate = calendar.getTime();
                                            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
                                            String formattedDate = dateFormat.format(specificDate);

                                            exifDirectory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, formattedDate);

                                            //Save modified file
                                            outputFile = new File(Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES).getPath() + "/exifEdit/" + fileName);
                                            OutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile.getAbsolutePath()));
                                            new ExifRewriter().updateExifMetadataLossy(tempFile, os, outputSet);  //Lossless makes exif grow and my crash app
                                            outputFile.setLastModified(originalDate);
                                        }

                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                } else {
                                    Toast.makeText(mContext, "Unable to create temporary file", Toast.LENGTH_SHORT).show();
                                }

                            }
                        }
                    }
                });
    }

    private void checkPermission() {
        String manifestPermission;
        if (Build.VERSION.SDK_INT <= 32){
            manifestPermission = Manifest.permission.READ_EXTERNAL_STORAGE;
        } else {
            manifestPermission = Manifest.permission.READ_MEDIA_IMAGES;
        }
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
        filePickerResultLauncher.launch(intent);
    }


    private File createTempFileFromUri(Uri uri) {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                return null;
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
        return tempFile;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tempFile!=null) tempFile.delete();
    }

}
