package com.expedientesclinicos.service.paciente;

import com.expedientesclinicos.dto.common.ModificadorRequest;
import com.expedientesclinicos.dto.paciente.SesionClinicaCreateRequest;
import com.expedientesclinicos.dto.paciente.SesionClinicaResponse;
import com.expedientesclinicos.dto.paciente.SesionClinicaUpdateRequest;
import com.expedientesclinicos.exception.AccessDeniedException;
import com.expedientesclinicos.exception.DomainException;
import com.expedientesclinicos.exception.ResourceNotFoundException;
import com.expedientesclinicos.model.paciente.Paciente;
import com.expedientesclinicos.model.paciente.SesionClinica;
import com.expedientesclinicos.model.paciente.SesionClinicaHistory;
import com.expedientesclinicos.model.paciente.Terapeuta;
import com.expedientesclinicos.repository.paciente.PacienteRepository;
import com.expedientesclinicos.repository.paciente.SesionClinicaHistoryRepository;
import com.expedientesclinicos.repository.paciente.SesionClinicaRepository;
import com.expedientesclinicos.service.util.PdfService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Transactional
public class SesionClinicaService {

    private final PacienteRepository pacienteRepository;
    private final SesionClinicaRepository sesionClinicaRepository;
    private final SesionClinicaHistoryRepository sesionClinicaHistoryRepository;
    private final PdfService pdfService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SesionClinicaService(PacienteRepository pacienteRepository,
                                SesionClinicaRepository sesionClinicaRepository,
                                SesionClinicaHistoryRepository sesionClinicaHistoryRepository,
                                PdfService pdfService) {
        this.pacienteRepository = pacienteRepository;
        this.sesionClinicaRepository = sesionClinicaRepository;
        this.sesionClinicaHistoryRepository = sesionClinicaHistoryRepository;
        this.pdfService = pdfService;
    }

    public SesionClinicaResponse registrarSesion(Long pacienteId, SesionClinicaCreateRequest request) {
        validarSolicitante(request.getSolicitante());
        Paciente paciente = buscarPaciente(pacienteId);
        validarAccesoSesion(paciente, request.getSolicitante());

        Terapeuta terapeutaAsignado = paciente.getTerapeutaAsignado();
        if (terapeutaAsignado == null) {
            throw new DomainException("El paciente no tiene terapeuta asignado");
        }
        if (!Objects.equals(terapeutaAsignado.getId(), request.getTerapeutaId())) {
            throw new DomainException("La sesión debe registrarse con el terapeuta asignado");
        }

        SesionClinica sesion = new SesionClinica();
        mapearSesion(request, sesion);
        sesion.setPaciente(paciente);
        sesion.setTerapeuta(terapeutaAsignado);

        sesionClinicaRepository.save(sesion);
        return mapearRespuesta(sesion);
    }

    public SesionClinicaResponse actualizarSesion(Long pacienteId, Long sesionId, SesionClinicaUpdateRequest request) {
        validarSolicitante(request.getSolicitante());
        Paciente paciente = buscarPaciente(pacienteId);
        validarAccesoSesion(paciente, request.getSolicitante());

        SesionClinica sesion = sesionClinicaRepository.findByIdAndPacienteId(sesionId, pacienteId)
                .orElseThrow(() -> new ResourceNotFoundException("Sesión no encontrada para el paciente"));

        // guardar snapshot antes de actualizar
        guardarHistoria(sesion, request.getSolicitante(), "Actualización");

        Terapeuta terapeutaAsignado = paciente.getTerapeutaAsignado();
        if (terapeutaAsignado == null) {
            throw new DomainException("El paciente no tiene terapeuta asignado");
        }
        if (!Objects.equals(terapeutaAsignado.getId(), request.getTerapeutaId())) {
            throw new DomainException("La sesión debe mantenerse con el terapeuta asignado");
        }

        mapearSesion(request, sesion);
        sesion.setTerapeuta(terapeutaAsignado);
        return mapearRespuesta(sesion);
    }

    @Transactional(readOnly = true)
    public List<SesionClinicaHistory> listarHistorial(Long pacienteId, Long sesionId, ModificadorRequest solicitante) {
        validarSolicitante(solicitante);
        Paciente paciente = buscarPaciente(pacienteId);
        validarAccesoSesion(paciente, solicitante);
        return sesionClinicaHistoryRepository.findBySesionIdOrderByFechaModificacionDesc(sesionId);
    }

    public SesionClinicaResponse revertirSesion(Long pacienteId, Long sesionId, Long historyId, ModificadorRequest solicitante) {
        validarSolicitante(solicitante);
        Paciente paciente = buscarPaciente(pacienteId);
        validarAccesoSesion(paciente, solicitante);

        SesionClinicaHistory history = sesionClinicaHistoryRepository.findById(historyId)
                .orElseThrow(() -> new ResourceNotFoundException("Versión de sesión no encontrada"));
        if (!Objects.equals(history.getSesionId(), sesionId) || !Objects.equals(history.getPacienteId(), pacienteId)) {
            throw new DomainException("La versión no corresponde a la sesión/paciente indicado");
        }

        SesionClinica sesion = sesionClinicaRepository.findByIdAndPacienteId(sesionId, pacienteId)
                .orElseThrow(() -> new ResourceNotFoundException("Sesión no encontrada para el paciente"));

        // guardar estado actual antes de revertir
        guardarHistoria(sesion, solicitante, "Antes de revertir a historyId=" + historyId);

        try {
            SesionClinicaResponse snapshot = objectMapper.readValue(history.getSnapshot(), SesionClinicaResponse.class);
            // aplicar campos
            sesion.setNumeroSesion(snapshot.getNumeroSesion());
            sesion.setTipoSesion(snapshot.getTipoSesion());
            sesion.setFecha(snapshot.getFecha());
            sesion.setAsistencia(snapshot.getAsistencia());
            sesion.setDuracionMinutos(snapshot.getDuracionMinutos());
            sesion.setMotivoCancelacion(snapshot.getMotivoCancelacion());
            sesion.setDescripcion(snapshot.getDescripcion());
            sesion.setObservaciones(snapshot.getObservaciones());
        } catch (Exception ex) {
            throw new DomainException("No se pudo restaurar la versión: " + ex.getMessage());
        }
        return mapearRespuesta(sesion);
    }

    @Transactional(readOnly = true)
    public byte[] generarPdfSesion(Long pacienteId, Long sesionId, ModificadorRequest solicitante) {
        validarSolicitante(solicitante);
        Paciente paciente = buscarPaciente(pacienteId);
        validarAccesoSesion(paciente, solicitante);

        SesionClinica sesion = sesionClinicaRepository.findByIdAndPacienteId(sesionId, pacienteId)
                .orElseThrow(() -> new ResourceNotFoundException("Sesión no encontrada para el paciente"));

        SesionClinicaResponse response = mapearRespuesta(sesion);
        return pdfService.generarPdfDesdeSesion(response);
    }

    private void guardarHistoria(SesionClinica sesion, ModificadorRequest solicitante, String comentario) {
        try {
            SesionClinicaHistory history = new SesionClinicaHistory();
            history.setSesionId(sesion.getId());
            history.setPacienteId(sesion.getPaciente() == null ? null : sesion.getPaciente().getId());
            String snapshot = objectMapper.writeValueAsString(mapearRespuesta(sesion));
            history.setSnapshot(snapshot);
            history.setFechaModificacion(LocalDateTime.now());
            history.setModificadoPor(solicitante == null ? null : solicitante.getUsuarioId());
            history.setComentario(comentario);
            sesionClinicaHistoryRepository.save(history);
        } catch (Exception ex) {
            // no bloquear la operación principal por fallos en el historial
        }
    }

    @Transactional(readOnly = true)
    public List<SesionClinicaResponse> listarSesiones(Long pacienteId, ModificadorRequest solicitante) {
        validarSolicitante(solicitante);
        Paciente paciente = buscarPaciente(pacienteId);
        validarAccesoSesion(paciente, solicitante);

        return sesionClinicaRepository.findByPacienteId(pacienteId)
                .stream()
                .map(this::mapearRespuesta)
                .collect(Collectors.toList());
    }

    private void mapearSesion(SesionClinicaCreateRequest request, SesionClinica sesion) {
        sesion.setNumeroSesion(request.getNumeroSesion());
        sesion.setTipoSesion(request.getTipoSesion());
        sesion.setFecha(request.getFecha());
        sesion.setAsistencia(request.getAsistencia());
        sesion.setDuracionMinutos(request.getDuracionMinutos());
        sesion.setMotivoCancelacion(request.getMotivoCancelacion());
        sesion.setDescripcion(request.getDescripcion());
        sesion.setObservaciones(request.getObservaciones());
    }

    private SesionClinicaResponse mapearRespuesta(SesionClinica sesion) {
        SesionClinicaResponse response = new SesionClinicaResponse();
        response.setId(sesion.getId());
        response.setNumeroSesion(sesion.getNumeroSesion());
        response.setTipoSesion(sesion.getTipoSesion());
        response.setFecha(sesion.getFecha());
        response.setAsistencia(sesion.getAsistencia());
        response.setDuracionMinutos(sesion.getDuracionMinutos());
        response.setMotivoCancelacion(sesion.getMotivoCancelacion());
        response.setDescripcion(sesion.getDescripcion());
        response.setObservaciones(sesion.getObservaciones());
        return response;
    }

    private Paciente buscarPaciente(Long pacienteId) {
        return pacienteRepository.findById(pacienteId)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente no encontrado"));
    }

    private void validarAccesoSesion(Paciente paciente, ModificadorRequest solicitante) {
        if (solicitante.esAdministrador()) {
            return;
        }
        Terapeuta terapeuta = paciente.getTerapeutaAsignado();
        if (terapeuta == null || !Objects.equals(terapeuta.getId(), solicitante.getUsuarioId())) {
            throw new AccessDeniedException("Solo el terapeuta asignado o un administrador puede gestionar sesiones");
        }
    }

    private void validarSolicitante(ModificadorRequest solicitante) {
        if (solicitante == null || solicitante.getUsuarioId() == null || solicitante.getPerfil() == null) {
            throw new DomainException("Debe indicar el solicitante que realiza la operación");
        }
    }
}
