package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Design
import com.example.data.DesignDao
import com.example.data.DesignDatabase
import com.example.utils.defaultDesign
import com.example.util.OperationTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class DesignsViewModel(
    private val designDao: DesignDao
) : ViewModel() {

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = DesignDatabase.getInstance(context)
            return DesignsViewModel(db.designDao()) as T
        }
    }
    
    // قائمة التصاميم
    private val _designs = MutableStateFlow<List<Design>>(emptyList())
    val designs: StateFlow<List<Design>> = _designs.asStateFlow()
    
    // التصميم المحدد حالياً
    private val _selectedDesign = MutableStateFlow<Design?>(null)
    val selectedDesign: StateFlow<Design?> = _selectedDesign.asStateFlow()
    
    // المسودة (للتحرير المباشر)
    private val _draftDesign = MutableStateFlow<Design?>(null)
    val draftDesign: StateFlow<Design?> = _draftDesign.asStateFlow()
    
    // حالة التحميل
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    init {
        loadDesigns()
    }
    
    fun loadDesigns() {
        viewModelScope.launch {
            _isLoading.update { true }
            try {
                designDao.getAllDesigns().collect { list ->
                    _designs.update { list }
                    if (list.isNotEmpty() && _selectedDesign.value == null) {
                        selectDesign(list.first().id)
                    } else if (list.isEmpty()) {
                        // Create an initial default design if none exists
                        val initDesign = defaultDesign(id = UUID.randomUUID().toString(), name = "التصميم الافتراضي")
                        designDao.insertDesign(initDesign)
                    }
                }
            } catch (e: Exception) {
                recordDesignFailure(OperationTelemetry.Operation.DESIGN_LOAD, e)
            } finally {
                _isLoading.update { false }
            }
        }
    }
    
    fun selectDesign(designId: String) {
        viewModelScope.launch {
            try {
                designDao.getDesignById(designId).collect { design ->
                    _selectedDesign.update { design }
                    _draftDesign.update { null } // مسح المسودة عند اختيار تصميم جديد
                }
            } catch (e: Exception) {
                recordDesignFailure(OperationTelemetry.Operation.DESIGN_SELECT, e)
            }
        }
    }
    
    fun saveDesign(design: Design) {
        viewModelScope.launch {
            _isLoading.update { true }
            try {
                designDao.insertDesign(design)
                _selectedDesign.update { design }
                _draftDesign.update { null }
            } catch (e: Exception) {
                recordDesignFailure(OperationTelemetry.Operation.DESIGN_SAVE, e)
            } finally {
                _isLoading.update { false }
            }
        }
    }
    
    fun deleteDesign(designId: String) {
        viewModelScope.launch {
            _isLoading.update { true }
            try {
                designDao.deleteDesignById(designId)
                if (_selectedDesign.value?.id == designId) {
                    _selectedDesign.update { null }
                    _draftDesign.update { null }
                }
            } catch (e: Exception) {
                recordDesignFailure(OperationTelemetry.Operation.DESIGN_DELETE, e)
            } finally {
                _isLoading.update { false }
            }
        }
    }
    
    fun createNewDesign(name: String? = null) {
        viewModelScope.launch {
            try {
                val count = designDao.getDesignCount()
                val newDesign = defaultDesign(
                    id = UUID.randomUUID().toString(),
                    name = name ?: "تصميم_${count + 1}"
                )
                _draftDesign.update { newDesign }
                saveDesign(newDesign)
            } catch (e: Exception) {
                recordDesignFailure(OperationTelemetry.Operation.DESIGN_CREATE, e)
            }
        }
    }
    
    fun updateDraft(design: Design) {
        _draftDesign.update { design }
    }
    
    fun getActiveDesign(): Design? {
        return _draftDesign.value ?: _selectedDesign.value
    }

    private fun recordDesignFailure(operation: OperationTelemetry.Operation, error: Throwable) {
        OperationTelemetry.record(
            event = OperationTelemetry.Event.SYNC_RESULT,
            operation = operation,
            outcome = OperationTelemetry.Outcome.FAILURE,
            errorClass = OperationTelemetry.classify(error)
        )
    }
}
