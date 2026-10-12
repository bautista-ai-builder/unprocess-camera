package com.reilandeubank.unprocess.fragments

import android.Manifest
import android.content.ContentUris
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.reilandeubank.unprocess.R
import com.reilandeubank.unprocess.databinding.FragmentGalleryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryFragment : Fragment() {
    private var binding: FragmentGalleryBinding? = null
    private val photos = mutableListOf<Photo>()
    private val photoAdapter = PhotoAdapter()
    private val rawThumbCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return FragmentGalleryBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val ui = binding ?: return
        ui.backButton.setOnClickListener { findNavController().navigateUp() }
        ui.photoGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        ui.photoGrid.adapter = photoAdapter
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 29 && Build.VERSION.SDK_INT >= 23 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 40)
        } else {
            refresh()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 40) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) refresh()
            else binding?.emptyMessage?.apply {
                text = "Permití el acceso a fotos para ver la galería."
                visibility = View.VISIBLE
            }
        }
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { queryPhotos() }
            photos.clear()
            photos.addAll(found)
            photoAdapter.notifyDataSetChanged()
            binding?.emptyMessage?.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun queryPhotos(): List<Photo> {
        val resolver = requireContext().contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE)
        val (selection, args) = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Images.Media.RELATIVE_PATH}=?" to arrayOf("DCIM/Unprocess/")
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/DCIM/Unprocess/%")
        }
        val found = mutableListOf<Photo>()
        resolver.query(collection, projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                found.add(Photo(ContentUris.withAppendedId(collection, cursor.getLong(idIndex)),
                    cursor.getString(nameIndex) ?: "Foto", cursor.getString(typeIndex) ?: "image/*"))
            }
        }
        return found
    }

    private fun openPhoto(photo: Photo) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(photo.uri, photo.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (exc: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No hay un visor compatible instalado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private data class Photo(val uri: Uri, val name: String, val mimeType: String)

    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.photo_image)
            val title: TextView = view.findViewById(R.id.photo_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

        override fun getItemCount(): Int = photos.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val photo = photos[position]
            holder.title.text = photo.name
            holder.itemView.tag = photo.uri
            Glide.with(holder.image).clear(holder.image)
            if (photo.mimeType == "image/jpeg") {
                Glide.with(holder.image).load(photo.uri).centerCrop().into(holder.image)
            } else {
                val key = photo.uri.toString()
                val cached = rawThumbCache.get(key)
                if (cached != null) {
                    holder.image.setImageBitmap(cached)
                } else {
                    holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
                    viewLifecycleOwner.lifecycleScope.launch {
                        val thumbnail = withContext(Dispatchers.IO) {
                            runCatching {
                                loadDngThumbnail(requireContext().contentResolver, photo.uri)
                            }.getOrNull()
                        }
                        if (thumbnail != null) {
                            rawThumbCache.put(key, thumbnail)
                            if (holder.itemView.tag == photo.uri) holder.image.setImageBitmap(thumbnail)
                        }
                    }
                }
            }
            holder.itemView.setOnClickListener { openPhoto(photo) }
        }
    }
}
