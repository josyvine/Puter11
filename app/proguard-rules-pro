# These rules fix the crash when analyzing PDF files.

# Rule 1: Prevents the PDF library's code from being altered or removed by the build process.
-keep class com.tom_roush.pdfbox.** { *; }

# Rule 2: Prevents the PDF library's essential resource files (like glyphlist.txt) 
# from being deleted by the build process. This is the direct fix for the crash.
-keepresources com/tom_roush/pdfbox/resources/**