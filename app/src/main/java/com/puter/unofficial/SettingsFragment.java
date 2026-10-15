package com.puter.unofficial;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.puter.unofficial.databinding.FragmentSettingsBinding;

/**
 * Handles the User Settings for Puter Unofficial.
 * Manages the Sign-In/Sign-Out persistence logic.
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private SharedPreferences prefs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        prefs = requireContext().getSharedPreferences("PuterPrefs", Context.MODE_PRIVATE);

        // Load current status
        updateUI();

        // Sign Out Logic
        binding.btnSignOut.setOnClickListener(v -> {
            prefs.edit().putBoolean("is_logged_in", false).apply();
            Toast.makeText(getContext(), "Logged out of Puter", Toast.LENGTH_SHORT).show();
            updateUI();
            
            // Reload WebView via activity method if necessary
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).reloadWebView();
            }
        });
    }

    private void updateUI() {
        boolean isLoggedIn = prefs.getBoolean("is_logged_in", false);
        if (isLoggedIn) {
            binding.tvStatus.setText("Status: Signed In");
            binding.btnSignOut.setVisibility(View.VISIBLE);
        } else {
            binding.tvStatus.setText("Status: Not Signed In");
            binding.btnSignOut.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}