package mygrant;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PrototypeController {

    @GetMapping("/")
    public String prototype() {
        return "redirect:/mygrant-prototype.html";
    }
}
