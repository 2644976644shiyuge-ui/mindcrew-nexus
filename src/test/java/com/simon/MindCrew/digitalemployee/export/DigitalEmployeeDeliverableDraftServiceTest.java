package com.simon.MindCrew.digitalemployee.export;

import com.simon.MindCrew.digitalemployee.dto.DeliverableDraftDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DigitalEmployeeDeliverableDraftServiceTest {

    @Test
    void shouldSplitDenseSlideIntoReadableContinuationPages() {
        DigitalEmployeeDeliverableDraftService service =
                new DigitalEmployeeDeliverableDraftService(null, null, null, null, null);
        DeliverableDraftDTO draft = new DeliverableDraftDTO();
        DeliverableDraftDTO.Slide slide = new DeliverableDraftDTO.Slide();
        slide.setTitle("经营分析");
        slide.setBullets(new ArrayList<>(List.of(
                "要点1", "要点2", "要点3", "要点4", "要点5", "要点6",
                "要点7", "要点8", "要点9", "要点10", "要点11", "要点12", "要点13"
        )));
        draft.setSlides(new ArrayList<>(List.of(slide)));

        service.normalizePptSlides(draft);

        assertEquals(3, draft.getSlides().size());
        assertEquals(6, draft.getSlides().get(0).getBullets().size());
        assertEquals(6, draft.getSlides().get(1).getBullets().size());
        assertEquals(1, draft.getSlides().get(2).getBullets().size());
        assertEquals("经营分析（续1）", draft.getSlides().get(1).getTitle());
    }
}
