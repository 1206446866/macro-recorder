package com.macro;


import com.macro.recognition.OpenCvTemplateMatcher;
import com.macro.ui.MacroFrame;

import javax.swing.*;


public class MacroApplication {

    public static void main(String[] args) {
        OpenCvTemplateMatcher.preloadInBackground();
        SwingUtilities.invokeLater(() ->
                new MacroFrame().setVisible(true)
        );
    }

}
